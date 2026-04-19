package com.chatapp.model.enums;

/**
 * Phương thức xác thực của user — được ghi vào JWT claim "auth_method".
 *
 * Giá trị string (lowercase) khớp với field auth_method trong JWT Payload
 * đã define ở docs/ARCHITECTURE.md mục 2.
 *
 * Dùng getValue() để lấy string khi set JWT claim, tránh dùng name() hay ordinal().
 */
public enum AuthMethod {

    PASSWORD("password"),
    OAUTH2_GOOGLE("oauth2_google");

    private final String value;

    AuthMethod(String value) {
        this.value = value;
    }

    /**
     * Trả string lowercase dùng trong JWT claim "auth_method".
     */
    public String getValue() {
        return value;
    }
}
