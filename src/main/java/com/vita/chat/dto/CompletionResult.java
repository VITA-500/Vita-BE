   package com.vita.chat.dto;

   public record CompletionResult(String text, Long promptTokens, Long completionTokens, Long totalTokens) {
   }