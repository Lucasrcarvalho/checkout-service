package com.checkout.nexo.bff.error;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorButton(String label, String url) {
}
