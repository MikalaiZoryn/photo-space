package com.photospace.shared.paging;

import java.util.List;
import java.util.function.Function;

/** One page of a list, with the total so the UI can show "26–50 of 60". */
public record PageView<T>(List<T> items, int page, int size, long totalItems) {

    public static <T> PageView<T> of(List<T> items, PageQuery query, long totalItems) {
        return new PageView<>(List.copyOf(items), query.page(), query.size(), totalItems);
    }

    public <R> PageView<R> map(Function<T, R> mapper) {
        return new PageView<>(items.stream().map(mapper).toList(), page, size, totalItems);
    }
}
