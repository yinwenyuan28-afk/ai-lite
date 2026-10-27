(function () {
  var root = "/ai-lite/admin/api";

  function api(path, options) {
    return fetch(root + path, options || {}).then(function (response) {
      return response.json().then(function (body) {
        if (!response.ok || body.ok === false) {
          throw new Error(body.message || "请求失败");
        }
        return body.data;
      });
    });
  }

  function post(path, body) {
    return api(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body || {})
    });
  }

  function put(path, body) {
    return api(path, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body || {})
    });
  }

  function el(tag, text) {
    var node = document.createElement(tag);
    if (text != null) {
      node.textContent = text;
    }
    return node;
  }

  function field(label, input) {
    var wrap = el("label");
    wrap.appendChild(el("span", label));
    wrap.appendChild(input);
    return wrap;
  }

  function status(parent) {
    var node = el("p", "");
    node.className = "status";
    parent.appendChild(node);
    return function (text, ok) {
      node.textContent = text || "";
      node.className = "status" + (text ? (ok ? " ok" : " error") : "");
    };
  }

  document.querySelectorAll("nav button").forEach(function (button) {
    button.addEventListener("click", function () {
      document.querySelectorAll("nav button").forEach(function (item) {
        item.setAttribute("aria-selected", item === button ? "true" : "false");
      });
      document.querySelectorAll(".panel").forEach(function (panel) {
        panel.classList.toggle("active", panel.id === button.getAttribute("data-tab"));
      });
    });
  });

  function loadProviders() {
    var panel = document.getElementById("providers");
    panel.textContent = "";
    api("/providers").then(function (providers) {
      if (!providers.length) {
        var empty = el("p", "配置文件里还没有供应商。");
        empty.className = "empty";
        panel.appendChild(empty);
        return;
      }
      providers.forEach(function (provider) {
        panel.appendChild(providerCard(provider));
      });
    }).catch(function (error) {
      panel.appendChild(el("p", error.message));
    });
  }

  function providerCard(provider) {
    var card = el("details");
    card.className = "card";
    card.appendChild(el("summary", provider.name));

    var content = el("div");
    content.className = "details-content";

    var note = "协议 " + (provider.api || "") + "。密钥" +
      (provider.apiKeyConfigured ? "已配置（" + provider.apiKeyMasked + "）" : "未配置") +
      "。留空表示不改这一项。";
    if (provider.keyPool) {
      note += " 已配置密钥池，这里填写的密钥不会替换密钥池。";
    }
    var noteNode = el("p", note);
    noteNode.className = "note";
    content.appendChild(noteNode);

    var grid = el("div");
    grid.className = "grid";
    var baseUrl = input("url", provider.baseUrl || "");
    var apiKey = input("password", "");
    apiKey.placeholder = "留空表示不改";
    var connect = input("number", provider.connectTimeoutMs);
    var read = input("number", provider.readTimeoutMs);
    grid.appendChild(field("地址", baseUrl));
    grid.appendChild(field("密钥", apiKey));
    grid.appendChild(field("连接超时（毫秒）", connect));
    grid.appendChild(field("读取超时（毫秒）", read));
    content.appendChild(grid);

    var actions = el("div");
    actions.className = "actions";
    var save = button("保存覆盖", true);
    var clear = button("清除覆盖", false);
    var probe = button("测试连通", false);
    var sync = button("同步模型", false);
    actions.appendChild(save);
    actions.appendChild(clear);
    actions.appendChild(probe);
    actions.appendChild(sync);
    content.appendChild(actions);
    var report = status(content);

    save.addEventListener("click", function () {
      post("/providers/" + encodeURIComponent(provider.name) + "/overlay", {
        baseUrl: baseUrl.value,
        apiKey: apiKey.value,
        connectTimeoutMs: connect.value,
        readTimeoutMs: read.value
      }).then(function () {
        report("覆盖已写入数据库。", true);
        loadProviders();
      }).catch(function (error) {
        report(error.message, false);
      });
    });
    clear.addEventListener("click", function () {
      post("/providers/" + encodeURIComponent(provider.name) + "/overlay/clear", {})
        .then(function () {
          report("已清除覆盖，连接信息回到配置文件。", true);
          loadProviders();
        }).catch(function (error) {
          report(error.message, false);
        });
    });
    probe.addEventListener("click", function () {
      report("正在测试连通…", true);
      post("/providers/" + encodeURIComponent(provider.name) + "/connectivity", {})
        .then(function (data) {
          report((data.ok ? "连通成功" : "连通失败") + "，HTTP " + data.httpStatus +
            "，" + data.latencyMs + " 毫秒。" + (data.message || ""), data.ok);
        }).catch(function (error) {
          report(error.message, false);
        });
    });
    sync.addEventListener("click", function () {
      report("正在读取远端模型，还不会写入…", true);
      post("/providers/" + encodeURIComponent(provider.name) + "/models/sync", {})
        .then(function (data) {
          report("", true);
          openSyncDialog(provider, data.models || []);
        }).catch(function (error) {
          report(error.message, false);
        });
    });

    content.appendChild(modelTable(provider));
    card.appendChild(content);
    return card;
  }

  function modelTable(provider) {
    var block = el("div");
    block.className = "table-section";
    var head = el("div");
    head.className = "section-head";
    head.appendChild(el("h2", "模型"));
    var headActions = el("div");
    headActions.className = "actions";
    var add = button("新增模型", true);
    headActions.appendChild(add);
    head.appendChild(headActions);
    block.appendChild(head);
    var tryStatus = status(block);
    var tryDump = el("div");
    tryDump.className = "try-dump";
    block.appendChild(tryDump);
    add.addEventListener("click", function () {
      openModelDialog(provider, null);
    });

    var table = el("table");
    table.appendChild(row("th", ["模型", "状态", "推理", "上下文", "输出上限", "思考格式", ""]));
    (provider.models || []).forEach(function (model) {
      var state = model.ready ? "可用" : "未就绪";
      if (model.duplicate) {
        state += "，重复";
      }
      var line = el("tr");
      [model.displayName || model.modelId, state, model.reasoning ? "是" : "否",
        model.contextWindow == null ? "" : String(model.contextWindow),
        model.maxTokens == null ? "" : String(model.maxTokens),
        model.thinkingFormat || ""].forEach(function (value) {
        line.appendChild(el("td", value));
      });
      var actionsCell = el("td");
      actionsCell.className = "cell-actions";
      var rowActions = el("div");
      rowActions.className = "row-actions";
      var edit = button(model.ready ? "修改" : "补齐", false);
      var test = button("测试调用", false);
      var remove = button("删除", false);
      remove.classList.add("danger");
      rowActions.appendChild(edit);
      rowActions.appendChild(test);
      rowActions.appendChild(remove);
      actionsCell.appendChild(rowActions);
      line.appendChild(actionsCell);
      edit.addEventListener("click", function () {
        openModelDialog(provider, model);
      });
      test.addEventListener("click", function () {
        tryStatus("正在调用 " + model.modelId + "…", true);
        post("/providers/" + encodeURIComponent(provider.name) + "/models/"
          + encodeURIComponent(model.modelId) + "/try", {})
          .then(function (data) {
            var reply = data.content ? "回复：" + data.content : (data.message || "");
            tryStatus((data.ok ? "调用成功" : "调用失败") + "，" + data.latencyMs + " 毫秒。" + reply, !!data.ok);
            showTryDump(tryDump, data);
          }).catch(function (error) {
            tryStatus(error.message, false);
          });
      });
      remove.addEventListener("click", function () {
        if (!window.confirm("删除模型 " + model.modelId + "？已有模型不会因为同步而消失，只有这里能删。")) {
          return;
        }
        fetch(root + "/providers/" + encodeURIComponent(provider.name) + "/models/"
          + encodeURIComponent(model.modelId), { method: "DELETE" })
          .then(function (response) { return response.json(); })
          .then(function (body) {
            if (!body.ok) {
              throw new Error(body.message || "删除失败");
            }
            loadProviders();
          }).catch(function (error) {
            window.alert(error.message);
          });
      });
      table.appendChild(line);
    });
    var wrap = el("div");
    wrap.className = "table-wrap";
    wrap.appendChild(table);
    block.appendChild(wrap);
    return block;
  }

  function showTryDump(dump, data) {
    dump.textContent = "";
    dump.appendChild(el("p", "发给模型的请求"));
    var request = el("pre", data.request || "");
    request.className = "log-text";
    dump.appendChild(request);
    dump.appendChild(el("p", "模型原始响应"));
    var response = el("pre", data.response || "");
    response.className = "log-text";
    dump.appendChild(response);
  }

  function openSyncDialog(provider, models) {
    var body = el("div");
    var intro = el("p", "勾选要加入本地列表的模型。已经在列表里的保持原样，未出现在这次结果里的也不会被删除。");
    intro.className = "note";
    body.appendChild(intro);
    if (!models.length) {
      body.appendChild(el("p", "远端没有返回模型。"));
    }
    var boxes = [];
    models.forEach(function (model) {
      var label = el("label");
      label.className = "checkbox-row";
      var box = el("input");
      box.type = "checkbox";
      box.checked = !model.stored;
      box.disabled = !!model.stored;
      label.appendChild(box);
      label.appendChild(el("span", model.modelId + (model.stored ? "（已在列表中）" : "")));
      body.appendChild(label);
      boxes.push({ box: box, model: model });
    });
    openDialog("选择要加入的模型", body, "加入列表", function (dialog) {
      var ids = [];
      boxes.forEach(function (item) {
        if (item.box.checked && !item.model.stored) {
          ids.push(item.model.modelId);
        }
      });
      if (!ids.length) {
        dialog.close();
        return;
      }
      post("/providers/" + encodeURIComponent(provider.name) + "/models/adopt", { modelIds: ids })
        .then(function () {
          dialog.close();
          loadProviders();
        }).catch(function (error) {
          window.alert(error.message);
        });
    });
  }

  function openModelDialog(provider, model) {
    var body = el("div");
    body.className = "grid";
    var modelId = input("text", model ? model.modelId : "");
    if (model) {
      modelId.readOnly = true;
    }
    var reasoning = el("select");
    reasoning.appendChild(option("true", "支持推理"));
    reasoning.appendChild(option("false", "不支持推理"));
    reasoning.value = model && model.reasoning ? "true" : "false";
    var contextWindow = input("number", model && model.contextWindow != null ? model.contextWindow : "32768");
    var maxTokens = input("number", model && model.maxTokens != null ? model.maxTokens : "4096");
    var format = el("select");
    var help = el("p", "思考格式决定请求体里怎么写思考开关。选一项后看下面的说明。");
    help.className = "note";
    body.appendChild(field("模型 id", modelId));
    body.appendChild(field("推理", reasoning));
    body.appendChild(field("上下文窗口，0 表示未知", contextWindow));
    body.appendChild(field("输出上限", maxTokens));
    body.appendChild(field("思考格式", format));
    body.appendChild(help);
    api("/thinking-formats").then(function (formats) {
      formats.forEach(function (item) {
        var node = option(item.id, item.label);
        node.setAttribute("data-detail", item.detail || "");
        format.appendChild(node);
      });
      if (model && model.thinkingFormat) {
        format.value = model.thinkingFormat;
      }
      showFormatHelp();
    }).catch(function (error) {
      help.textContent = error.message;
    });
    format.addEventListener("change", showFormatHelp);
    function showFormatHelp() {
      var selected = format.options[format.selectedIndex];
      help.textContent = selected && selected.getAttribute("data-detail")
        ? selected.getAttribute("data-detail")
        : "思考格式决定请求体里怎么写思考开关。";
    }
    openDialog(model ? "修改模型" : "新增模型", body, "保存", function (dialog) {
      var payload = {
        reasoning: reasoning.value === "true",
        contextWindow: Number(contextWindow.value),
        maxTokens: Number(maxTokens.value),
        thinkingFormat: format.value
      };
      var path = "/providers/" + encodeURIComponent(provider.name) + "/models";
      var ready = model
        ? Promise.resolve()
        : post(path, { modelId: modelId.value });
      ready.then(function () {
        return put(path + "/" + encodeURIComponent(modelId.value), payload);
      }).then(function () {
        dialog.close();
        loadProviders();
      }).catch(function (error) {
        window.alert(error.message);
      });
    });
  }

  function openDialog(title, body, confirmLabel, onConfirm) {
    var dialog = document.getElementById("dialog");
    dialog.textContent = "";
    var header = el("div");
    header.className = "dialog-header";
    header.appendChild(el("h2", title));
    dialog.appendChild(header);
    var bodyWrap = el("div");
    bodyWrap.className = "dialog-body";
    bodyWrap.appendChild(body);
    dialog.appendChild(bodyWrap);
    var footer = el("div");
    footer.className = "dialog-footer";
    var cancel = button("取消", false);
    var ok = button(confirmLabel, true);
    footer.appendChild(cancel);
    footer.appendChild(ok);
    dialog.appendChild(footer);
    cancel.addEventListener("click", function () { dialog.close(); });
    ok.addEventListener("click", function () { onConfirm(dialog); });
    dialog.showModal();
  }

  function loadBiz() {
    var panel = document.getElementById("biz");
    panel.textContent = "";
    Promise.all([api("/biz"), api("/providers")]).then(function (result) {
      var bizList = result[0];
      var providers = result[1];
      var ready = [];
      providers.forEach(function (provider) {
        (provider.models || []).forEach(function (model) {
          if (model.ready) {
            ready.push({ providerId: provider.name, modelId: model.modelId });
          }
        });
      });
      if (!bizList.length) {
        panel.appendChild(el("p", "配置文件里还没有业务 id。"));
        return;
      }
      bizList.forEach(function (biz) {
        panel.appendChild(bizCard(biz, ready));
      });
    }).catch(function (error) {
      panel.appendChild(el("p", error.message));
    });
  }

  function bizCard(biz, ready) {
    var card = el("section");
    card.className = "card";
    var cardBody = el("div");
    cardBody.className = "card-body";
    cardBody.appendChild(el("h2", biz.bizId));
    var bizNote = el("p", biz.promptActive ? "已有生效提示词。" : "还没有生效提示词。");
    bizNote.className = "note";
    cardBody.appendChild(bizNote);

    var bindingBlock = el("div");
    bindingBlock.className = "form-block";
    var model = el("select");
    if (!ready.length) {
      model.appendChild(option("", "还没有可用模型"));
    }
    ready.forEach(function (item) {
      var value = item.providerId + "\n" + item.modelId;
      var optionNode = option(value, item.providerId + " / " + item.modelId);
      if (biz.binding && biz.binding.providerId === item.providerId && biz.binding.modelId === item.modelId) {
        optionNode.selected = true;
      }
      model.appendChild(optionNode);
    });
    var level = el("select");
    ["off", "minimal", "low", "medium", "high", "xhigh", "max"].forEach(function (value) {
      var optionNode = option(value, value);
      if (biz.binding && biz.binding.thinkingLevel === value) {
        optionNode.selected = true;
      }
      level.appendChild(optionNode);
    });
    var grid = el("div");
    grid.className = "grid";
    grid.appendChild(field("模型", model));
    grid.appendChild(field("思考档", level));
    bindingBlock.appendChild(grid);
    var bindActions = el("div");
    bindActions.className = "actions";
    var saveBinding = button("保存模型", true);
    bindActions.appendChild(saveBinding);
    bindingBlock.appendChild(bindActions);
    cardBody.appendChild(bindingBlock);
    var bindStatus = status(bindingBlock);
    saveBinding.addEventListener("click", function () {
      var parts = model.value.split("\n");
      put("/biz/" + encodeURIComponent(biz.bizId), {
        providerId: parts[0],
        modelId: parts[1],
        thinkingLevel: level.value
      }).then(function () {
        bindStatus("模型绑定已保存。", true);
      }).catch(function (error) {
        bindStatus(error.message, false);
      });
    });

    var promptBlock = el("div");
    promptBlock.className = "form-block";
    var system = el("textarea");
    var user = el("textarea");
    promptBlock.appendChild(field("系统模板", system));
    promptBlock.appendChild(field("用户模板", user));
    var promptActions = el("div");
    promptActions.className = "actions";
    var saveDraft = button("保存草稿", false);
    var publish = button("发布", true);
    promptActions.appendChild(saveDraft);
    promptActions.appendChild(publish);
    promptBlock.appendChild(promptActions);
    cardBody.appendChild(promptBlock);
    var promptStatus = status(promptBlock);
    var draftId = "";

    var historyBlock = el("div");
    historyBlock.className = "form-block";
    historyBlock.appendChild(el("h3", "版本记录"));
    var historyNote = el("p", "可载入历史内容到上方编辑，或把已退役版本回滚为生效。");
    historyNote.className = "note";
    historyBlock.appendChild(historyNote);
    var historyTable = el("table");
    var historyWrap = el("div");
    historyWrap.className = "table-wrap";
    historyWrap.appendChild(historyTable);
    historyBlock.appendChild(historyWrap);
    cardBody.appendChild(historyBlock);
    var historyStatus = status(historyBlock);

    function statusLabel(value) {
      if (value === "published") {
        return "生效";
      }
      if (value === "retired") {
        return "已退役";
      }
      if (value === "draft") {
        return "草稿";
      }
      return value || "";
    }

    function formatTime(ms) {
      if (ms == null) {
        return "";
      }
      var date = new Date(Number(ms));
      if (isNaN(date.getTime())) {
        return "";
      }
      function pad(n) {
        return n < 10 ? "0" + n : String(n);
      }
      return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate())
        + " " + pad(date.getHours()) + ":" + pad(date.getMinutes()) + ":" + pad(date.getSeconds());
    }

    function renderVersions(versions) {
      historyTable.textContent = "";
      historyTable.appendChild(row("th", ["版本", "状态", "创建时间", "操作人", ""]));
      if (!versions || !versions.length) {
        historyStatus("还没有版本记录。", true);
        return;
      }
      historyStatus("", true);
      versions.forEach(function (item) {
        var line = el("tr");
        ["v" + item.versionNo, statusLabel(item.status), formatTime(item.createTime),
          item.createdBy || ""].forEach(function (value) {
          line.appendChild(el("td", value));
        });
        var actionsCell = el("td");
        actionsCell.className = "cell-actions";
        var rowActions = el("div");
        rowActions.className = "row-actions";
        var load = button("载入", false);
        rowActions.appendChild(load);
        load.addEventListener("click", function () {
          system.value = item.systemTemplate || "";
          user.value = item.userTemplate || "";
          if (item.status === "draft") {
            draftId = item.versionId;
            promptStatus("已载入草稿 v" + item.versionNo + "，可继续编辑或发布。", true);
          } else {
            draftId = "";
            promptStatus("已载入 v" + item.versionNo + " 的内容到编辑区。要改的话请先保存为新草稿。", true);
          }
        });
        if (item.status === "draft") {
          var publishOne = button("发布", true);
          rowActions.appendChild(publishOne);
          publishOne.addEventListener("click", function () {
            post("/biz/" + encodeURIComponent(biz.bizId) + "/prompt/publish",
              { versionId: item.versionId })
              .then(function () {
                draftId = "";
                promptStatus("已发布 v" + item.versionNo + "。", true);
                return reloadPrompt();
              }).catch(function (error) {
                promptStatus(error.message, false);
              });
          });
        } else if (item.status === "retired") {
          var rollback = button("回滚生效", false);
          rowActions.appendChild(rollback);
          rollback.addEventListener("click", function () {
            if (!window.confirm("把 v" + item.versionNo + " 重新设为生效？")) {
              return;
            }
            post("/biz/" + encodeURIComponent(biz.bizId) + "/prompt/rollback",
              { versionId: item.versionId })
              .then(function () {
                promptStatus("已回滚到 v" + item.versionNo + "。", true);
                return reloadPrompt();
              }).catch(function (error) {
                promptStatus(error.message, false);
              });
          });
        }
        actionsCell.appendChild(rowActions);
        line.appendChild(actionsCell);
        historyTable.appendChild(line);
      });
    }

    function reloadPrompt() {
      return api("/biz/" + encodeURIComponent(biz.bizId) + "/prompt").then(function (prompt) {
        var source = prompt.draft || prompt.active;
        if (source) {
          system.value = source.systemTemplate || "";
          user.value = source.userTemplate || "";
        }
        draftId = prompt.draft ? prompt.draft.versionId : "";
        renderVersions(prompt.versions || []);
        bizNote.textContent = prompt.active ? "已有生效提示词。" : "还没有生效提示词。";
        return prompt;
      });
    }

    reloadPrompt().catch(function (error) {
      promptStatus(error.message, false);
    });

    saveDraft.addEventListener("click", function () {
      post("/biz/" + encodeURIComponent(biz.bizId) + "/prompt/draft", {
        systemTemplate: system.value,
        userTemplate: user.value
      }).then(function (draft) {
        draftId = draft.versionId;
        promptStatus("草稿已保存，尚未生效。", true);
        return reloadPrompt();
      }).catch(function (error) {
        promptStatus(error.message, false);
      });
    });
    publish.addEventListener("click", function () {
      if (!draftId) {
        promptStatus("先保存草稿，再发布。", false);
        return;
      }
      post("/biz/" + encodeURIComponent(biz.bizId) + "/prompt/publish", { versionId: draftId })
        .then(function () {
          draftId = "";
          promptStatus("已发布，业务渲染会读到这份模板。", true);
          return reloadPrompt();
        }).catch(function (error) {
          promptStatus(error.message, false);
        });
    });

    card.appendChild(cardBody);
    return card;
  }

  function loadCalls() {
    var panel = document.getElementById("calls");
    panel.textContent = "";
    var card = el("section");
    card.className = "card";
    var cardBody = el("div");
    cardBody.className = "card-body";
    cardBody.appendChild(el("h2", "调用日志"));
    var callsNote = el("p", "只显示摘要。输入输出是否保存，由下面的记录方式决定。");
    callsNote.className = "note";
    cardBody.appendChild(callsNote);
    var levelRow = el("div");
    levelRow.className = "filters";
    var level = el("select");
    var saveLevel = button("保存记录方式", false);
    levelRow.appendChild(level);
    levelRow.appendChild(saveLevel);
    cardBody.appendChild(levelRow);
    var levelStatus = status(cardBody);
    api("/calls/body-level").then(function (data) {
      (data.options || []).forEach(function (item) {
        level.appendChild(option(item.id, item.label));
      });
      level.value = data.level;
    }).catch(function (error) {
      levelStatus(error.message, false);
    });
    saveLevel.addEventListener("click", function () {
      put("/calls/body-level", { level: level.value }).then(function () {
        levelStatus("记录方式已保存，之后的调用按这个档位写。", true);
      }).catch(function (error) {
        levelStatus(error.message, false);
      });
    });
    var filters = el("div");
    filters.className = "filters";
    var bizId = input("text", "");
    bizId.placeholder = "业务 id";
    var provider = input("text", "");
    provider.placeholder = "供应商";
    var limit = input("number", "50");
    var query = button("查询", true);
    filters.appendChild(bizId);
    filters.appendChild(provider);
    filters.appendChild(limit);
    filters.appendChild(query);
    cardBody.appendChild(filters);
    var report = status(cardBody);
    var table = el("table");
    var tableWrap = el("div");
    tableWrap.className = "table-wrap";
    tableWrap.appendChild(table);
    cardBody.appendChild(tableWrap);
    card.appendChild(cardBody);
    panel.appendChild(card);

    function run() {
      var path = "/calls?limit=" + encodeURIComponent(limit.value || "50");
      if (bizId.value) {
        path += "&bizId=" + encodeURIComponent(bizId.value);
      }
      if (provider.value) {
        path += "&provider=" + encodeURIComponent(provider.value);
      }
      api(path).then(function (rows) {
        table.textContent = "";
        table.appendChild(row("th", ["时间", "业务", "供应商", "模型", "结果", "耗时", "摘要", "输入输出"]));
        if (!rows.length) {
          report("没有记录。", true);
          return;
        }
        report("", true);
        rows.forEach(function (item) {
          var line = el("tr");
          [item.CREATE_TIME || "", item.BIZ_ID || "", item.PROVIDER || "", item.MODEL_ID || "",
            item.SUCCESS === 1 || item.SUCCESS === true ? "成功" : "失败",
            item.LATENCY_MS == null ? "" : String(item.LATENCY_MS),
            item.ERROR_BRIEF || item.STOP_REASON || ""].forEach(function (value) {
            line.appendChild(el("td", value));
          });
          var bodyCell = el("td");
          if (item.INPUT_TEXT || item.OUTPUT_TEXT) {
            var more = el("details");
            more.className = "log-details";
            more.appendChild(el("summary", "查看"));
            var pre = el("pre");
            pre.className = "log-text";
            pre.textContent = "输入\n" + (item.INPUT_TEXT || "") + "\n\n输出\n" + (item.OUTPUT_TEXT || "");
            more.appendChild(pre);
            bodyCell.appendChild(more);
          }
          line.appendChild(bodyCell);
          table.appendChild(line);
        });
      }).catch(function (error) {
        report(error.message, false);
      });
    }
    query.addEventListener("click", run);
    run();
  }

  function input(type, value) {
    var node = el("input");
    node.type = type;
    node.value = value == null ? "" : String(value);
    return node;
  }

  function button(text, primary) {
    var node = el("button", text);
    node.type = "button";
    node.className = "btn";
    if (primary) {
      node.classList.add("primary");
    }
    return node;
  }

  function option(value, text) {
    var node = el("option", text);
    node.value = value;
    return node;
  }

  function row(cell, values) {
    var node = el("tr");
    values.forEach(function (value) {
      node.appendChild(el(cell, value));
    });
    return node;
  }

  loadProviders();
  document.querySelector('[data-tab="biz"]').addEventListener("click", loadBiz);
  document.querySelector('[data-tab="calls"]').addEventListener("click", loadCalls);
})();
