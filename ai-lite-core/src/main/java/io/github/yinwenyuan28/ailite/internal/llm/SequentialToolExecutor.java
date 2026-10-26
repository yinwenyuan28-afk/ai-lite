package io.github.yinwenyuan28.ailite.internal.llm;

import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.ToolExecutor;

/**
 * 串行工具执行器。每个工具按模型给出的顺序执行，异常转成文本回给模型。
 */
public final class SequentialToolExecutor implements ToolExecutor {

    @Override
    public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
        List<String> results = new ArrayList<String>();
        if (calls == null) {
            return results;
        }
        for (LlmToolCall call : calls) {
            results.add(runOne(tools, call));
        }
        return results;
    }

    private static String runOne(List<LlmTool> tools, LlmToolCall call) {
        LlmTool tool = find(tools, call == null ? null : call.getName());
        if (tool == null) {
            return "未找到工具: " + Strings.defaultString(call == null ? null : call.getName());
        }
        Map<String, Object> arguments;
        try {
            String json = call.getArgumentsJson();
            arguments = Strings.isBlank(json) ? new java.util.LinkedHashMap<String, Object>()
                    : AiLiteJson.readMap(json);
        } catch (RuntimeException ex) {
            return "工具参数不是合法 JSON";
        }
        try {
            String text = tool.execute(arguments);
            return text == null ? "" : text;
        } catch (RuntimeException ex) {
            return "工具执行失败";
        }
    }

    private static LlmTool find(List<LlmTool> tools, String name) {
        if (tools == null || name == null) {
            return null;
        }
        for (LlmTool tool : tools) {
            if (tool != null && name.equals(tool.name())) {
                return tool;
            }
        }
        return null;
    }
}
