package com.medpilot.common;

/** 统一响应封装：{ success, data, error, meta }。 */
public record ApiResponse<T>(boolean success, T data, String error, Object meta) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> ok(T data, Object meta) {
        return new ApiResponse<>(true, data, null, meta);
    }

    public static <T> ApiResponse<T> fail(String error) {
        return new ApiResponse<>(false, null, error, null);
    }

    public static <T> ApiResponse<T> fail(String error, T data) {
        return new ApiResponse<>(false, data, error, null);
    }
}
