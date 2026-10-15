package com.vita.chat.service;

/**
 * 프롬프트에 넣는 데이터의 태그 문자를 바꾼다.
 * 데이터 안에 "</document>", "</question>" 같은 글자가 있어도 구분 태그가
 * 닫히지 않게 해서 데이터가 지시문으로 읽히는 것을 막는다.
 */
public class PromptEscaper {

    private PromptEscaper() {}

    /** & < > 를 &amp; &lt; &gt; 로 바꾼다. */
    public static String escape(String text){
        if(text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
