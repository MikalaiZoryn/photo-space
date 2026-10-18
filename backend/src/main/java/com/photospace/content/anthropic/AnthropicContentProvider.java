package com.photospace.content.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.photospace.content.ContentGenerationException;
import com.photospace.content.ContentGenerationException.Kind;
import com.photospace.content.ContentGenerationProvider;
import com.photospace.content.ContentPrompt;
import com.photospace.content.ContentProperties;
import com.photospace.content.GenerationRequest;
import com.photospace.content.GenerationResult;
import com.photospace.content.PlanRequest;
import com.photospace.content.PlanResult;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drafts and session plans from Claude through the Messages API. One request per batch: each photo's preview as a JPEG image
 * labelled with its reference, then the escaped brand profile, session type, and notes, with the answer
 * constrained to a JSON schema. SDK types and messages stay in this class: callers see a
 * {@link GenerationResult} or a {@link ContentGenerationException} with a provider-neutral kind.
 *
 * <p>Refused requests are re-routed server-side ({@code fallbacks: "default"}); a refusal that remains is a
 * permanent failure.
 */
class AnthropicContentProvider implements ContentGenerationProvider {

    static final String PROVIDER = "anthropic";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> STRINGS = Map.of("type", "array", "items", STRING);
    private static final Map<String, Object> CAPTION = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("caption", "call_to_action", "hashtags"),
            "properties", Map.of("caption", STRING, "call_to_action", STRING, "hashtags", STRINGS));
    private static final JsonOutputFormat FORMAT = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .putAdditionalProperty("required", JsonValue.from(
                            List.of("captions", "carousel_order", "carousel_rationale", "story_ideas", "warnings")))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "captions", Map.of("type", "array", "items", CAPTION),
                            "carousel_order", STRINGS,
                            "carousel_rationale", STRING,
                            "story_ideas", STRINGS,
                            "warnings", STRINGS)))
                    .build())
            .build();

    private static final Map<String, Object> PLAN_POST = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("slot", "photos", "caption", "call_to_action", "hashtags", "idea"),
            "properties", Map.of("slot", Map.of("type", "integer"), "photos", STRINGS, "caption", STRING,
                    "call_to_action", STRING, "hashtags", STRINGS, "idea", STRING));
    private static final JsonOutputFormat PLAN_FORMAT = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .putAdditionalProperty("required", JsonValue.from(List.of("posts", "warnings")))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "posts", Map.of("type", "array", "items", PLAN_POST),
                            "warnings", STRINGS)))
                    .build())
            .build();

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final AnthropicClient client;
    private final ContentProperties.Anthropic properties;

    AnthropicContentProvider(AnthropicClient client, ContentProperties.Anthropic properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        Message message = send(params(request));
        Output output = read(message, Output.class);
        List<GenerationResult.Caption> captions = output.captions() == null ? List.of() : output.captions().stream()
                .filter(c -> c != null)
                .map(c -> new GenerationResult.Caption(c.caption(), c.callToAction(), c.hashtags()))
                .toList();
        return new GenerationResult(captions, output.carouselOrder(), output.carouselRationale(),
                output.storyIdeas(), output.warnings(), (int) message.usage().inputTokens(),
                (int) message.usage().outputTokens());
    }

    @Override
    public PlanResult plan(PlanRequest request) {
        Message message = send(params(request.photos(), ContentPrompt.planInstructions(request),
                ContentPrompt.planSystem(), PLAN_FORMAT));
        PlanOutput output = read(message, PlanOutput.class);
        List<PlanResult.Post> posts = output.posts() == null ? List.of() : output.posts().stream()
                .filter(p -> p != null)
                .map(p -> new PlanResult.Post(p.slot(), p.photos(), p.caption(), p.callToAction(), p.hashtags(),
                        p.idea()))
                .toList();
        return new PlanResult(posts, output.warnings(), (int) message.usage().inputTokens(),
                (int) message.usage().outputTokens());
    }

    MessageCreateParams params(GenerationRequest request) {
        return params(request.photos(), ContentPrompt.instructions(request), ContentPrompt.system(), FORMAT);
    }

    /** Each photo's preview labelled with its reference, then the escaped data and request, with a JSON schema. */
    MessageCreateParams params(List<GenerationRequest.Photo> photos, String instructions, String system,
            JsonOutputFormat format) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (GenerationRequest.Photo photo : photos) {
            blocks.add(ContentBlockParam.ofText(photo.ref() + ":"));
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(Base64.getEncoder().encodeToString(photo.jpeg()))
                            .build())
                    .build()));
        }
        blocks.add(ContentBlockParam.ofText(instructions));
        return MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                .system(system)
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(properties.effort()))
                        .format(format)
                        .build())
                .addUserMessageOfBlockParams(blocks)
                .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
    }

    private Message send(MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (AnthropicException e) {
            throw new ContentGenerationException(kind(e), e);
        }
    }

    /** The answer's JSON as {@code type}; a refusal, a cut-off answer, or unreadable JSON is a provider-neutral kind. */
    private static <T> T read(Message message, Class<T> type) {
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new ContentGenerationException(Kind.REFUSED);
        }
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            throw new ContentGenerationException(Kind.INVALID_OUTPUT);
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
        T output;
        try {
            output = JSON.readValue(text, type);
        } catch (JacksonException e) {
            throw new ContentGenerationException(Kind.INVALID_OUTPUT, e);
        }
        if (output == null) {
            throw new ContentGenerationException(Kind.INVALID_OUTPUT);
        }
        return output;
    }

    /** Most specific first: what can be retried later, and what needs someone to change the setup or input. */
    static Kind kind(AnthropicException e) {
        return switch (e) {
            case RateLimitException ignored -> Kind.RATE_LIMITED;
            case UnauthorizedException ignored -> Kind.NOT_CONFIGURED;
            case PermissionDeniedException ignored -> Kind.NOT_CONFIGURED;
            case NotFoundException ignored -> Kind.NOT_CONFIGURED; // An unknown model.
            case BadRequestException ignored -> Kind.REJECTED;
            case UnprocessableEntityException ignored -> Kind.REJECTED;
            case InternalServerException ignored -> Kind.UNAVAILABLE;
            case AnthropicServiceException service ->
                    service.statusCode() == 408 || service.statusCode() == 409 || service.statusCode() >= 500
                            ? Kind.UNAVAILABLE
                            : Kind.REJECTED;
            case AnthropicIoException ignored -> Kind.UNAVAILABLE;
            case AnthropicInvalidDataException ignored -> Kind.INVALID_OUTPUT;
            default -> Kind.UNAVAILABLE;
        };
    }

    /** The answer as the JSON schema above defines it. */
    record Output(
            List<OutputCaption> captions,
            List<String> carouselOrder,
            String carouselRationale,
            List<String> storyIdeas,
            List<String> warnings) {}

    record OutputCaption(String caption, String callToAction, List<String> hashtags) {}

    /** A plan as {@link #PLAN_FORMAT} defines it. */
    record PlanOutput(List<PlanOutputPost> posts, List<String> warnings) {}

    record PlanOutputPost(
            Integer slot, List<String> photos, String caption, String callToAction, List<String> hashtags,
            String idea) {}
}
