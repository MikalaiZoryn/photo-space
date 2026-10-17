package com.photospace.dashboard;

import jakarta.validation.constraints.NotNull;

/** One widget of a saved layout. Its position is its place in the request's list. */
record WidgetInput(@NotNull DashboardWidget type, @NotNull WidgetWidth width) {}
