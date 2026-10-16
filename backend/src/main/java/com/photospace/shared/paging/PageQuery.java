package com.photospace.shared.paging;

/** A zero-based page of a list. Controllers validate the bounds; see {@link #MAX_SIZE}. */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;
    public static final int MAX_PAGE = 10_000;

    public PageQuery {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Page out of bounds");
        }
    }

    public long offset() {
        return (long) page * size;
    }
}
