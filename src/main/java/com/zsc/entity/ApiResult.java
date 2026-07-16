package com.zsc.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ApiResult<T> {
    private int code;       // 业务状态码，0 表示成功，非0失败
    private String message; // 提示信息
    private T data;         // 实际数据

    // 快速构造成功结果
    public static <T> ApiResult<T> success(T data) {
        ApiResult<T> result = new ApiResult<>();
        result.code = 0;
        result.message = "success";
        result.data = data;
        return result;
    }

    public static <T> ApiResult<T> success() {
        return success(null);
    }

    public static <T> ApiResult<T> error(int code, String message) {
        ApiResult<T> result = new ApiResult<>();
        result.code = code;
        result.message = message;
        return result;
    }

    // getter/setter 省略
}
