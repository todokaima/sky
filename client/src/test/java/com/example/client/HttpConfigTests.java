package com.example.client;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpConfigTests {

    @Test
    void sendsTheFieldsAndTheOtherHeaders() {
        HttpConfig config = new HttpConfig();
        config.setJwt("abc.def.ghi");
        config.setHeaders("X-Tenant: shop\n# X-Debug: 1");

        assertEquals(Map.of("X-Tenant", "shop", "Accept", "application/json", "Content-Type", "application/json",
                "Authorization", "Bearer abc.def.ghi"), config.headerMap());
    }

    @Test
    void keepsOnlyTheTokenOfAMessyPaste() {
        assertEquals("abc.def.ghi", HttpConfig.cleanJwt("  \"Bearer abc.de\n  f.ghi \"\n"));
        assertEquals("abc.def.ghi", HttpConfig.cleanJwt("bearer\tabc.def.ghi"));
        assertEquals("", HttpConfig.cleanJwt(null));
    }

    @Test
    void anEmptyFieldIsNotSentAndAFilledOneWinsOverTheFreeText() {
        HttpConfig config = new HttpConfig();
        config.setAccept("");
        config.setContentType("text/plain");
        config.setHeaders("content-type: application/xml\nAuthorization: Basic dTpw");

        assertEquals(Map.of("Content-Type", "text/plain", "Authorization", "Basic dTpw"), config.headerMap());
    }

    @Test
    void anOldConfigsHeaderLinesMoveIntoTheFields() {
        HttpConfig config = new HttpConfig();
        config.setAccept("application/json");
        config.setHeaders("Accept: */*\r\nContent-Type: text/plain\r\nAuthorization: Bearer abc.def.ghi\r\nX-Tenant: shop\r\n# Authorization: Bearer <paste-jwt-here>");

        config.liftFormedHeaders();

        assertEquals("*/*", config.getAccept());
        assertEquals("text/plain", config.getContentType());
        assertEquals("abc.def.ghi", config.getJwt());
        assertEquals("X-Tenant: shop\n# Authorization: Bearer <paste-jwt-here>", config.getHeaders());
    }
}
