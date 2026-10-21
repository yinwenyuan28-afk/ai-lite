package io.github.yinwenyuan28.ailite.prompt;

/**
 * 一套不可变的提示词模板。系统模板必须非空，用户模板可以为空。
 */
public final class PromptTemplate {

    private final String systemTemplate;
    private final String userTemplate;
    private final String outputSchema;

    public PromptTemplate(String systemTemplate, String userTemplate, String outputSchema) {
        this.systemTemplate = systemTemplate == null ? "" : systemTemplate;
        this.userTemplate = userTemplate == null ? "" : userTemplate;
        this.outputSchema = outputSchema;
    }

    public String getSystemTemplate() {
        return systemTemplate;
    }

    public String getUserTemplate() {
        return userTemplate;
    }

    public String getOutputSchema() {
        return outputSchema;
    }
}
