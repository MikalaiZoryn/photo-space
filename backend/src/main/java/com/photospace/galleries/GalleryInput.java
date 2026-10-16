package com.photospace.galleries;

import com.photospace.shared.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The editable settings of a gallery. Status changes through its own endpoint; photos through theirs. */
record GalleryInput(@NotBlank @Size(max = 200) String title, @NotNull GalleryDownloads downloads) {

    GalleryInput {
        title = Text.strip(title);
    }
}
