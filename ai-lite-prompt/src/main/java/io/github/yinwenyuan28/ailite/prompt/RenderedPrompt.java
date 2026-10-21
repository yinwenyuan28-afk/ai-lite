package io.github.yinwenyuan28.ailite.prompt;

import io.github.yinwenyuan28.ailite.llm.LlmMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 渲染结果。直接给出 system/user 文本和网关可用的消息列表。
 */
public final class RenderedPrompt {

    private final String system;
    private final String user;
    private final List<LlmMessage> messages;

    public RenderedPrompt(String system, String user) {
        this.system = system == null ? "" : system;
        this.user = user == null ? "" : user;
        List<LlmMessage> list = new ArrayList<LlmMessage>();
        if (!this.system.isEmpty()) {
            list.add(LlmMessage.system(this.system));
        }
        if (!this.user.isEmpty()) {
            list.add(LlmMessage.user(this.user));
        }
        this.messages = Collections.unmodifiableList(list);
    }

    public String getSystem() {
        return system;
    }

    public String getUser() {
        return user;
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }
}
