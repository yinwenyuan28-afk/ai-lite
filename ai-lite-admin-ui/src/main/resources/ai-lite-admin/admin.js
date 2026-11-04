/*
 * AI Lite 管理页。
 *
 * 约定：
 * - 所有可写操作都是「改 → 显式点按钮 → 才写库」，切换开关（就绪）除外，
 *   它是明确的单次动作，点了立刻写并显示结果。
 * - 页面不使用原生 select / checkbox / confirm / alert：浏览器默认控件
 *   在深色和不同系统上观感差异太大，统一由下面的组件渲染。
 * - 任何服务端文本都通过 textContent 写入，不拼 HTML。
 */
(function () {
  "use strict";

  var API_ROOT = "/ai-lite/admin/api";
  /** 思考档 id 列表。测试调用与业务绑定用它们拼下拉。 */
  var THINKING_LEVEL_IDS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];
  var INPUT_MODES = [
    { id: "text", label: "文本" },
    { id: "image", label: "图片" },
    { id: "audio", label: "音频" },
    { id: "video", label: "视频" },
    { id: "file", label: "文件" }
  ];

  /**
   * 思考档的展示项，供行编辑表当左列下拉用。
   *
   * <p>与 {@link THINKING_LEVEL_IDS} 分开：那个是纯 id 数组，旧的测试调用弹窗
   * 直接拿它拼下拉；这个是 { id, label } 结构，行编辑表的 select 需要。
   * 曾用同一个名字定义两次，后者覆盖前者，导致旧弹窗把整个对象当成 id，
   * 选项显示成 [object Object]。</p>
   */
  var THINKING_LEVEL_OPTIONS = [
    { id: "off", label: "off（关闭）" },
    { id: "minimal", label: "minimal" },
    { id: "low", label: "low" },
    { id: "medium", label: "medium" },
    { id: "high", label: "high" },
    { id: "xhigh", label: "xhigh" },
    { id: "max", label: "max" }
  ];

  /**
   * 把接口返回的 Map 转成行编辑表要的数组。null 表示「没配」，返回空数组。
   *
   * @param map 形如 {"low": "low"} 的对象
   * @return [{key, value}]
   */
  function mapToRows(map) {
    if (!map) {
      return [];
    }
    return Object.keys(map).map(function (key) {
      var value = map[key];
      return { key: key, value: value == null ? "" : String(value) };
    });
  }

  /**
   * 把接口返回的对象转成 JSON 文本，供 JSON 输入框显示。
   *
   * @param value 对象或 null
   * @return 格式化后的 JSON 文本；null 返回空串
   */
  function jsonText(value) {
    if (value == null) {
      return "";
    }
    if (typeof value === "object" && Object.keys(value).length === 0) {
      return "";
    }
    return JSON.stringify(value, null, 2);
  }

  /* ---------------- HTTP ---------------- */

  function request(path, options) {
    return fetch(API_ROOT + path, options || {}).then(function (response) {
      return response.json().then(function (body) {
        if (!response.ok || body.ok === false) {
          throw new Error(body.message || "请求失败（HTTP " + response.status + "）");
        }
        return body.data;
      }, function () {
        throw new Error("响应不是合法 JSON（HTTP " + response.status + "）");
      });
    });
  }

  function send(method, path, payload) {
    var options = { method: method };
    if (payload !== undefined) {
      options.headers = { "Content-Type": "application/json" };
      options.body = JSON.stringify(payload || {});
    }
    return request(path, options);
  }

  function get(path) {
    return request(path);
  }

  function post(path, payload) {
    return send("POST", path, payload === undefined ? {} : payload);
  }

  function put(path, payload) {
    return send("PUT", path, payload === undefined ? {} : payload);
  }

  function del(path) {
    return send("DELETE", path);
  }

  function enc(value) {
    return encodeURIComponent(value == null ? "" : String(value));
  }

  /* ---------------- DOM 工具 ---------------- */

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text != null) {
      node.textContent = text;
    }
    return node;
  }

  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
    return node;
  }

  var ICONS = {
    check: '<path d="m5 12 4.5 4.5L19 7"/>',
    chevron: '<path d="m6 9 6 6 6-6"/>',
    close: '<path d="M18 6 6 18M6 6l12 12"/>',
    refresh: '<path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 4v5h-5"/>',
    checkCircle: '<circle cx="12" cy="12" r="9"/><path d="m8.5 12.5 2.5 2.5 4.5-5"/>',
    alert: '<circle cx="12" cy="12" r="9"/><path d="M12 8v4.5M12 16h.01"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    trash: '<path d="M4 7h16M9 7V5h6v2M6 7l1 13h10l1-13"/>',
    edit: '<path d="M4 20h4l10-10-4-4L4 16v4Z"/><path d="m14 6 4 4"/>',
    play: '<path d="M7 5v14l12-7Z"/>'
  };

  function icon(name, className) {
    var wrap = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    wrap.setAttribute("viewBox", "0 0 24 24");
    wrap.setAttribute("fill", "none");
    wrap.setAttribute("stroke", "currentColor");
    wrap.setAttribute("stroke-width", "1.8");
    wrap.setAttribute("stroke-linecap", "round");
    wrap.setAttribute("stroke-linejoin", "round");
    wrap.setAttribute("aria-hidden", "true");
    if (className) {
      wrap.setAttribute("class", className);
    }
    wrap.innerHTML = ICONS[name] || "";
    return wrap;
  }

  function button(text, options) {
    var opts = options || {};
    var node = el("button", "btn" + (opts.tone ? " btn-" + opts.tone : "")
      + (opts.size === "sm" ? " btn-sm" : ""));
    node.type = "button";
    if (opts.icon) {
      node.appendChild(icon(opts.icon));
    }
    if (text) {
      node.appendChild(el("span", null, text));
    }
    if (opts.title) {
      node.title = opts.title;
      node.setAttribute("aria-label", opts.title);
    }
    if (opts.onClick) {
      node.addEventListener("click", opts.onClick);
    }
    node.setDisabled = function (value) {
      node.disabled = !!value;
    };
    return node;
  }

  function field(label, control, hint) {
    var wrap = el("div", "field");
    if (label) {
      wrap.appendChild(el("span", "field-label", label));
    }
    var controlWrap = el("div", "field-control");
    controlWrap.appendChild(control);
    wrap.appendChild(controlWrap);
    if (hint) {
      var hintNode = el("p", "field-hint", hint);
      wrap.appendChild(hintNode);
      wrap.hintNode = hintNode;
    }
    return wrap;
  }

  function textInput(value, options) {
    var opts = options || {};
    var node = el("input", "input" + (opts.mono ? " mono" : ""));
    node.type = opts.type || "text";
    node.value = value == null ? "" : String(value);
    if (opts.placeholder) {
      node.placeholder = opts.placeholder;
    }
    if (opts.readOnly) {
      node.readOnly = true;
    }
    if (opts.min != null) {
      node.min = String(opts.min);
    }
    return node;
  }

  function textArea(value, options) {
    var opts = options || {};
    var node = el("textarea", "textarea" + (opts.mono ? " mono" : "") + (opts.tall ? " tall" : ""));
    node.value = value == null ? "" : String(value);
    if (opts.placeholder) {
      node.placeholder = opts.placeholder;
    }
    if (opts.rows) {
      node.rows = opts.rows;
    }
    return node;
  }

  function switchControl(checked, options) {
    var opts = options || {};
    var node = el("button", "switch");
    node.type = "button";
    node.setAttribute("role", "switch");
    node.setAttribute("aria-checked", checked ? "true" : "false");
    node.appendChild(el("span", "switch-thumb"));
    var state = !!checked;
    if (opts.title) {
      node.title = opts.title;
      node.setAttribute("aria-label", opts.title);
    }
    node.addEventListener("click", function () {
      state = !state;
      node.setAttribute("aria-checked", state ? "true" : "false");
      if (opts.onChange) {
        opts.onChange(state);
      }
    });
    node.getValue = function () { return state; };
    node.setValue = function (value) {
      state = !!value;
      node.setAttribute("aria-checked", state ? "true" : "false");
    };
    node.setBusy = function (busy) {
      node.classList.toggle("busy", !!busy);
      node.disabled = !!busy;
    };
    return node;
  }

  function fieldRow(title, desc, control) {
    var row = el("div", "field-row");
    var text = el("div", "field-row-text");
    text.appendChild(el("span", "field-row-title", title));
    if (desc) {
      text.appendChild(el("span", "field-row-desc", desc));
    }
    row.appendChild(text);
    row.appendChild(control);
    return row;
  }

  /* ---------------- 行编辑表 ---------------- */

  /**
   * 可增删行的键值编辑器，用于请求头和思考档映射。
   *
   * @param options.label      左侧标签
   * @param options.hint       说明文字
   * @param options.initial    初始行，形如 [{key: "...", value: "..."}]
   * @param options.keyOptions 左列的下拉选项；不传则左列是文本框
   * @param options.valuePlaceholder 右列占位符
   * @param options.onChange   行变化回调
   * @return 带 getValue() 的控件
   */
  function editableRows(options) {
    var opts = options || {};
    var wrap = el("div", "field");
    wrap.appendChild(el("span", "field-label", opts.label));
    var hint = el("p", "field-hint", opts.hint || "");
    var list = el("div", "rows-editor");
    var state = (opts.initial || []).map(function (item) {
      return { key: item.key == null ? "" : String(item.key),
               value: item.value == null ? "" : String(item.value) };
    });

    function render() {
      list.innerHTML = "";
      state.forEach(function (row, index) {
        var line = el("div", "rows-line");
        var keyControl;
        if (opts.keyOptions) {
          keyControl = select(opts.keyOptions, row.key, function (next) {
            row.key = next;
            if (opts.onChange) {
              opts.onChange();
            }
          });
        } else {
          keyControl = textInput(row.key, {
            placeholder: opts.keyPlaceholder || "名称",
            mono: true
          });
          keyControl.addEventListener("input", function () {
            row.key = keyControl.value;
            if (opts.onChange) {
              opts.onChange();
            }
          });
        }
        var valueControl = textInput(row.value, {
          placeholder: opts.valuePlaceholder || "值",
          mono: true
        });
        valueControl.addEventListener("input", function () {
          row.value = valueControl.value;
          if (opts.onChange) {
            opts.onChange();
          }
        });
        line.appendChild(keyControl);
        line.appendChild(valueControl);
        var remove = button("", {
          size: "sm",
          icon: "trash",
          title: "删除这一行",
          onClick: function () {
            state.splice(index, 1);
            render();
            if (opts.onChange) {
              opts.onChange();
            }
          }
        });
        line.appendChild(remove);
        list.appendChild(line);
      });
      var add = button("增加一行", {
        size: "sm",
        icon: "plus",
        onClick: function () {
          state.push({ key: "", value: "" });
          render();
          if (opts.onChange) {
            opts.onChange();
          }
        }
      });
      list.appendChild(add);
    }

    render();
    wrap.appendChild(list);
    wrap.appendChild(hint);
    wrap.hintNode = hint;
    wrap.getValue = function () {
      var result = {};
      var seen = {};
      state.forEach(function (row) {
        var key = row.key.trim();
        if (!key) {
          return;
        }
        var lower = key.toLowerCase();
        if (seen[lower]) {
          return;
        }
        seen[lower] = true;
        result[key] = row.value;
      });
      return result;
    };
    /** 左列是下拉时，右列留空表示「这一项不支持」；返回带 null 的映射。 */
    wrap.getValueAllowingNull = function () {
      var result = {};
      state.forEach(function (row) {
        var key = row.key.trim();
        if (!key) {
          return;
        }
        result[key] = row.value.trim() === "" ? null : row.value;
      });
      return result;
    };
    wrap.initialRows = state.length;
    return wrap;
  }

  /* ---------------- JSON 输入 ---------------- */

  /**
   * JSON 文本框，带格式化按钮与就地错误提示。
   *
   * @param options.label       标签
   * @param options.hint        说明
   * @param options.value       初始 JSON 文本（可为空）
   * @param options.onChange    内容变化回调
   * @return 带 getValue()（返回解析后的对象，语法错误时返回 undefined）的控件
   */
  function jsonField(options) {
    var opts = options || {};
    var wrap = el("div", "field");
    var head = el("div", "json-head");
    head.appendChild(el("span", "field-label", opts.label));
    var formatBtn = button("格式化", { size: "sm" });
    head.appendChild(formatBtn);
    wrap.appendChild(head);

    var area = textArea(opts.value || "", {
      mono: true,
      rows: opts.rows || 3,
      placeholder: opts.placeholder || "{}"
    });
    wrap.appendChild(area);

    var hint = el("p", "field-hint", opts.hint || "");
    wrap.appendChild(hint);
    wrap.hintNode = hint;

    var error = el("p", "field-hint error", "");
    error.hidden = true;
    wrap.appendChild(error);

    function parse() {
      var text = area.value.trim();
      if (!text) {
        error.hidden = true;
        return {};
      }
      try {
        var value = JSON.parse(text);
        if (value === null || typeof value !== "object" || Array.isArray(value)) {
          throw new Error("必须是 JSON 对象");
        }
        error.hidden = true;
        return value;
      } catch (ex) {
        error.textContent = "JSON 无法解析：" + ex.message;
        error.hidden = false;
        return undefined;
      }
    }

    area.addEventListener("input", function () {
      parse();
      if (opts.onChange) {
        opts.onChange();
      }
    });

    formatBtn.addEventListener("click", function () {
      var value = parse();
      if (value !== undefined) {
        area.value = JSON.stringify(value, null, 2);
      }
    });

    wrap.getValue = parse;
    return wrap;
  }

  function checkboxList(options, selectedIds, onChange) {
    var wrap = el("div", "checkbox-list");
    var selected = {};
    (selectedIds || []).forEach(function (id) { selected[id] = true; });
    options.forEach(function (item) {
      var node = el("button", "checkbox");
      node.type = "button";
      node.setAttribute("role", "checkbox");
      node.setAttribute("aria-checked", selected[item.id] ? "true" : "false");
      var box = el("span", "checkbox-box");
      box.appendChild(icon("check"));
      node.appendChild(box);
      node.appendChild(el("span", null, item.label));
      node.title = item.id;
      node.addEventListener("click", function () {
        var next = !selected[item.id];
        selected[item.id] = next;
        node.setAttribute("aria-checked", next ? "true" : "false");
        if (onChange) {
          onChange(node.getValue());
        }
      });
      wrap.appendChild(node);
    });
    wrap.getValue = function () {
      return options.filter(function (item) { return selected[item.id]; })
        .map(function (item) { return item.id; });
    };
    return wrap;
  }

  /* ---------------- 自定义下拉 ---------------- */

  /**
   * 自定义下拉。菜单用 fixed 定位挂在页面根部，
   * 避免被弹窗的滚动容器裁掉。
   */
  function select(options, value, onChange) {
    var items = options || [];
    var current = null;
    var wrap = el("div", "select");
    var trigger = el("button", "select-trigger");
    trigger.type = "button";
    trigger.setAttribute("aria-haspopup", "listbox");
    trigger.setAttribute("aria-expanded", "false");
    var valueNode = el("span", "select-value");
    trigger.appendChild(valueNode);
    trigger.appendChild(icon("chevron", "select-caret"));
    wrap.appendChild(trigger);

    var menu = el("div", "select-menu");
    menu.setAttribute("role", "listbox");
    menu.hidden = true;
    var activeIndex = -1;

    function labelOf(item) {
      return item ? item.label : "";
    }

    function renderValue() {
      valueNode.textContent = current ? labelOf(current) : "";
      valueNode.classList.toggle("placeholder", !current);
    }

    function closeMenu() {
      menu.hidden = true;
      wrap.setAttribute("data-open", "false");
      trigger.setAttribute("aria-expanded", "false");
      if (menu.parentNode) {
        menu.parentNode.removeChild(menu);
      }
      document.removeEventListener("mousedown", onOutside, true);
      document.removeEventListener("keydown", onKeydown, true);
      window.removeEventListener("resize", closeMenu);
      window.removeEventListener("scroll", closeMenu, true);
    }

    function positionMenu() {
      var rect = trigger.getBoundingClientRect();
      var width = Math.max(rect.width, 160);
      var left = Math.min(rect.left, window.innerWidth - width - 8);
      menu.style.width = width + "px";
      menu.style.left = Math.max(8, left) + "px";
      menu.style.top = "auto";
      menu.style.bottom = "auto";
      var spaceBelow = window.innerHeight - rect.bottom;
      if (spaceBelow < 240 && rect.top > spaceBelow) {
        menu.style.bottom = (window.innerHeight - rect.top + 5) + "px";
      } else {
        menu.style.top = (rect.bottom + 5) + "px";
      }
    }

    function onOutside(event) {
      if (!wrap.contains(event.target) && !menu.contains(event.target)) {
        closeMenu();
      }
    }

    /**
     * 让浮层菜单高于当前所有弹窗。
     *
     * <p>菜单为了不被 overflow 裁切而挂在 body 上，但 CSS 里它的 z-index 是 40，
     * 低于弹窗的 60。放在弹窗里打开时（测试调用选的思考档、编辑模型时的思考格式），
     * 菜单会被半透明遮罩盖住，看起来像"点不动"。弹窗还能叠弹窗（确认框叠在弹窗上），
     * 所以不能写死一个更高的值——按当前最上层弹窗动态加一。</p>
     */
    function raiseAboveModals() {
      var top = 0;
      var backdrops = document.querySelectorAll(".modal-backdrop");
      for (var i = 0; i < backdrops.length; i++) {
        var z = parseInt(window.getComputedStyle(backdrops[i]).zIndex, 10);
        if (!isNaN(z) && z > top) {
          top = z;
        }
      }
      if (top > 0) {
        menu.style.zIndex = String(top + 1);
      } else {
        menu.style.zIndex = "";
      }
    }

    function openMenu() {
      if (!menu.hidden) {
        return;
      }
      document.body.appendChild(menu);
      menu.hidden = false;
      raiseAboveModals();
      wrap.setAttribute("data-open", "true");
      trigger.setAttribute("aria-expanded", "true");
      positionMenu();
      activeIndex = items.indexOf(current);
      markActive();
      document.addEventListener("mousedown", onOutside, true);
      document.addEventListener("keydown", onKeydown, true);
      window.addEventListener("resize", closeMenu);
      window.addEventListener("scroll", closeMenu, true);
    }

    function markActive() {
      var buttons = menu.children;
      for (var i = 0; i < buttons.length; i++) {
        buttons[i].setAttribute("data-active", i === activeIndex ? "true" : "false");
      }
    }

    function onKeydown(event) {
      if (event.key === "Escape") {
        closeMenu();
        trigger.focus();
        return;
      }
      if (menu.hidden) {
        return;
      }
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        activeIndex += event.key === "ArrowDown" ? 1 : -1;
        activeIndex = Math.max(0, Math.min(items.length - 1, activeIndex));
        markActive();
      }
      if (event.key === "Enter" && activeIndex >= 0) {
        event.preventDefault();
        choose(items[activeIndex]);
      }
    }

    function choose(item) {
      current = item;
      renderValue();
      closeMenu();
      if (onChange) {
        onChange(item ? item.id : null, item);
      }
      wrap.changeListeners.forEach(function (listener) {
        listener(item ? item.id : null, item);
      });
    }

    items.forEach(function (item) {
      var option = el("button", "select-option");
      option.type = "button";
      option.setAttribute("role", "option");
      var textWrap = el("span");
      textWrap.appendChild(el("span", null, item.label));
      if (item.desc) {
        textWrap.appendChild(el("span", "select-option-desc", item.desc));
      }
      option.appendChild(textWrap);
      if (item.id === value) {
        option.setAttribute("aria-selected", "true");
      }
      option.addEventListener("click", function () {
        choose(item);
        trigger.focus();
      });
      menu.appendChild(option);
    });

    current = items.filter(function (item) { return item.id === value; })[0] || null;
    renderValue();
    if (!current) {
      valueNode.textContent = items.length ? "" : "没有可选项";
      valueNode.classList.add("placeholder");
    }
    trigger.addEventListener("click", function (event) {
      event.preventDefault();
      if (menu.hidden) {
        openMenu();
      } else {
        closeMenu();
      }
    });
    trigger.addEventListener("keydown", function (event) {
      if (event.key === "ArrowDown" && menu.hidden) {
        event.preventDefault();
        openMenu();
      }
    });
    wrap.getValue = function () { return current ? current.id : null; };
    wrap.setValue = function (next) {
      current = items.filter(function (item) { return item.id === next; })[0] || null;
      renderValue();
    };
    /**
     * 替换整个选项列表。异步拿到选项后用它重建菜单，
     * 并把当前值设到 fallback 上（选项里没有 fallback 时置空）。
     */
    wrap.setOptions = function (nextItems, fallback) {
      items = nextItems || [];
      clear(menu);
      items.forEach(function (item) {
        var option = el("button", "select-option");
        option.type = "button";
        option.setAttribute("role", "option");
        var textWrap = el("span");
        textWrap.appendChild(el("span", null, item.label));
        if (item.desc) {
          textWrap.appendChild(el("span", "select-option-desc", item.desc));
        }
        option.appendChild(textWrap);
        option.addEventListener("click", function () {
          choose(item);
          trigger.focus();
        });
        menu.appendChild(option);
      });
      current = items.filter(function (item) { return item.id === fallback; })[0] || null;
      renderValue();
      if (!current) {
        valueNode.textContent = items.length ? "" : "没有可选项";
        valueNode.classList.add("placeholder");
      }
    };
    /** 值变化后的监听。构造时的 onChange 之外，外部还可以订阅 change。 */
    wrap.changeListeners = [];
    wrap.addEventListener = function (type, listener) {
      if (type === "change") {
        wrap.changeListeners.push(listener);
      }
    };
    wrap.node = trigger;
    return wrap;
  }

  /* ---------------- 提示条 ---------------- */

  function toast(message, tone) {
    var stack = document.getElementById("toastStack");
    var node = el("div", "toast " + (tone || "info"));
    node.appendChild(icon(tone === "ok" ? "checkCircle" : tone === "error" ? "alert" : "info",
      "toast-icon"));
    node.appendChild(el("span", null, message));
    stack.appendChild(node);
    window.setTimeout(function () {
      node.style.opacity = "0";
      node.style.transition = "opacity .2s ease";
      window.setTimeout(function () {
        if (node.parentNode) {
          node.parentNode.removeChild(node);
        }
      }, 220);
    }, tone === "error" ? 6000 : 3200);
  }

  /* ---------------- 弹窗 ---------------- */

  function openModal(config) {
    var root = document.getElementById("modalRoot");
    var backdrop = el("div", "modal-backdrop");
    var panel = el("div", "modal-panel" + (config.wide ? " wide" : ""));
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-modal", "true");

    var header = el("div", "modal-header");
    header.appendChild(el("h2", "modal-title", config.title || ""));
    if (config.desc) {
      header.appendChild(el("p", "modal-desc", config.desc));
    }
    panel.appendChild(header);

    var body = el("div", "modal-body");
    if (config.body) {
      body.appendChild(config.body);
    }
    panel.appendChild(body);

    var footer = el("div", "modal-footer");
    panel.appendChild(footer);
    backdrop.appendChild(panel);
    root.appendChild(backdrop);

    function close() {
      if (backdrop.parentNode) {
        backdrop.parentNode.removeChild(backdrop);
      }
      document.removeEventListener("keydown", onKey, true);
    }

    function onKey(event) {
      if (event.key === "Escape") {
        event.stopPropagation();
        close();
      }
    }

    backdrop.addEventListener("mousedown", function (event) {
      if (event.target === backdrop) {
        close();
      }
    });
    document.addEventListener("keydown", onKey, true);

    var api = {
      el: panel,
      body: body,
      footer: footer,
      close: close,
      addAction: function (node) {
        footer.appendChild(node);
        return node;
      }
    };
    if (config.onMount) {
      config.onMount(api);
    }
    var focusTarget = panel.querySelector("input, textarea, button");
    if (focusTarget) {
      window.setTimeout(function () { focusTarget.focus(); }, 30);
    }
    return api;
  }

  /**
   * 自定义确认框。返回 Promise<boolean>，不用 window.confirm。
   */
  function confirmDialog(options) {
    return new Promise(function (resolve) {
      var decided = false;
      var body = el("p", "card-desc", options.message);
      var modal = openModal({
        title: options.title || "确认操作",
        body: body,
        onMount: function (dialog) {
          dialog.addAction(button("取消", {
            onClick: function () {
              decided = true;
              dialog.close();
              resolve(false);
            }
          }));
          dialog.addAction(button(options.confirmText || "确认", {
            tone: options.danger ? "danger" : "primary",
            onClick: function () {
              decided = true;
              dialog.close();
              resolve(true);
            }
          }));
        }
      });
      var originalClose = modal.close;
      modal.close = function () {
        originalClose();
        if (!decided) {
          decided = true;
          resolve(false);
        }
      };
    });
  }

  /* ---------------- 页签 ---------------- */

  var loaders = {
    providers: loadProviders,
    biz: loadBiz,
    calls: loadCalls
  };

  function switchTab(tabId) {
    document.querySelectorAll(".nav-item").forEach(function (item) {
      item.setAttribute("aria-selected", item.getAttribute("data-tab") === tabId ? "true" : "false");
    });
    document.querySelectorAll(".panel").forEach(function (panel) {
      panel.hidden = panel.id !== tabId;
    });
    loaders[tabId]();
  }

  document.querySelectorAll(".nav-item").forEach(function (item) {
    item.addEventListener("click", function () {
      switchTab(item.getAttribute("data-tab"));
    });
  });

  /* ---------------- 通用状态行 ---------------- */

  function statusLine(parent) {
    var node = el("p", "status-line");
    parent.appendChild(node);
    return function (text, tone) {
      node.textContent = text || "";
      node.className = "status-line" + (text ? " " + (tone || "info") : "");
    };
  }

  function emptyState(title, desc, action) {
    var wrap = el("div", "empty-state");
    wrap.appendChild(el("p", "empty-title", title));
    if (desc) {
      wrap.appendChild(el("p", "empty-desc", desc));
    }
    if (action) {
      var actions = el("div", "actions");
      actions.appendChild(action);
      wrap.appendChild(actions);
    }
    return wrap;
  }

  function badge(text, tone) {
    var node = el("span", "badge" + (tone ? " badge-" + tone : ""));
    node.appendChild(el("span", "badge-dot"));
    node.appendChild(el("span", null, text));
    return node;
  }

  /* ---------------- 供应商与模型 ---------------- */

  var expandedProviders = {};

  function loadProviders() {
    var panel = document.getElementById("providers");
    clear(panel);
    var loading = el("p", "status-line", "正在读取供应商…");
    panel.appendChild(loading);
    get("/providers").then(function (providers) {
      clear(panel);
      if (!providers.length) {
        panel.appendChild(emptyState("配置文件里还没有供应商",
          "只有 application.yml 中 ai-lite.llm.providers 声明过的供应商才会出现在这里。"));
        return;
      }
      providers.forEach(function (provider) {
        panel.appendChild(providerCard(provider));
      });
    }).catch(function (error) {
      clear(panel);
      panel.appendChild(emptyState("读取供应商失败", error.message,
        button("重试", { tone: "primary", onClick: loadProviders })));
    });
  }

  function providerCard(provider) {
    var name = provider.name;
    var open = !!expandedProviders[name];
    var card = el("section", "card");

    var disclosure = el("button", "disclosure");
    disclosure.type = "button";
    disclosure.setAttribute("aria-expanded", open ? "true" : "false");
    var main = el("div", "disclosure-main");
    main.appendChild(el("span", "disclosure-name", name));
    var meta = el("div", "disclosure-meta");
    meta.appendChild(el("span", null, "协议 " + (provider.api || "未声明")
      + (provider.apiOverridden ? "（库内覆盖）" : "")));
    meta.appendChild(el("span", null, "·"));
    meta.appendChild(el("span", null, provider.baseUrl || "未填地址"));
    var models = provider.models || [];
    var readyCount = models.filter(function (model) { return model.ready; }).length;
    var counts = el("div", "disclosure-meta");
    counts.appendChild(badge("可用 " + readyCount + " / " + models.length,
      readyCount ? "ready" : "off"));
    if (provider.apiKeyConfigured) {
      counts.appendChild(badge("密钥 " + provider.apiKeyMasked, "info"));
    } else {
      counts.appendChild(badge("密钥未配置", "warn"));
    }
    if (provider.overlay) {
      counts.appendChild(badge("库内覆盖生效", "warn"));
    }
    if (provider.keyPool) {
      counts.appendChild(badge("密钥池", "info"));
    }
    main.appendChild(meta);
    main.appendChild(counts);
    disclosure.appendChild(main);
    disclosure.appendChild(icon("chevron", "chevron"));
    card.appendChild(disclosure);

    var body = el("div", "disclosure-body");
    body.hidden = !open;
    disclosure.addEventListener("click", function () {
      open = !open;
      expandedProviders[name] = open;
      disclosure.setAttribute("aria-expanded", open ? "true" : "false");
      body.hidden = !open;
    });

    body.appendChild(connectionSection(provider));
    body.appendChild(modelsSection(provider));
    card.appendChild(body);
    return card;
  }

  function connectionSection(provider) {
    var section = el("div", "section");
    section.appendChild(el("h3", "section-title", "连接信息"));

    var note = el("p", "card-desc");
    note.textContent = "留空的字段表示沿用配置文件。"
      + (provider.keyPool ? "该供应商配置了密钥池，这里填写的密钥不会替换密钥池。" : "");
    section.appendChild(note);

    var grid = el("div", "form-grid");
    var baseUrl = textInput(provider.baseUrl, { placeholder: "https://example.com/v1" });
    var apiKey = textInput("", { type: "password", placeholder: "留空表示不改" });
    var connect = textInput(provider.connectTimeoutMs, { type: "number", min: 1 });
    var read = textInput(provider.readTimeoutMs, { type: "number", min: 1 });

    // 协议决定 base-url 之后追加什么路径，所以和地址放在一起。
    var apiOptions = [{ id: "", label: "正在读取…" }];
    var apiSelect = select(apiOptions, provider.api, function () {});
    var apiHint = el("p", "field-hint", "");
    var apiField = el("div", "field");
    apiField.appendChild(el("span", "field-label", "协议"));
    apiField.appendChild(apiSelect);
    apiField.appendChild(apiHint);
    grid.appendChild(apiField);
    grid.appendChild(field("地址", baseUrl, "只写到端点根地址，本库会自己接协议路径"));
    grid.appendChild(field("密钥", apiKey, provider.apiKeyConfigured
      ? "当前密钥 " + provider.apiKeyMasked + "，留空则不修改" : "尚未配置密钥"));
    grid.appendChild(field("连接超时（毫秒）", connect));
    grid.appendChild(field("读取超时（毫秒）", read));
    section.appendChild(grid);

    // 供应商级请求参数：与地址、密钥同处保存，因为它们都是「调用时带什么」。
    var providerHeadersField = editableRows({
      label: "供应商请求头" + (provider.providerHeadersOverridden ? "" : "（来自配置文件）"),
      hint: "该供应商下所有模型都会带这些头。与模型级同名（忽略大小写）时，模型级覆盖这里。"
        + "从这里保存会整份替换配置文件里的 headers，不是逐键叠加。"
        + "Authorization 与 Content-Type 由库管理，不能在这里配。",
      keyPlaceholder: "头名，如 X-Request-Source",
      valuePlaceholder: "头值",
      initial: mapToRows(provider.providerHeaders)
    });
    section.appendChild(providerHeadersField);
    if (!provider.providerHeadersOverridden
      && provider.providerHeaders && Object.keys(provider.providerHeaders).length) {
      var fromYamlNote = el("p", "field-hint",
        "当前值来自配置文件。保存任意一项覆盖后，这一份会成为数据库里的值。");
      section.appendChild(fromYamlNote);
    }

    var providerExtraField = jsonField({
      label: "供应商附加请求体" + (provider.providerExtraBodyOverridden ? "" : "（来自配置文件）"),
      hint: "该供应商下所有模型共用的私有字段，例如 vLLM 的 chat_template_kwargs。"
        + "保存会整份替换配置文件里的 extra-body。",
      value: jsonText(provider.providerExtraBody),
      placeholder: "{\"chat_template_kwargs\": {\"enable_thinking\": true}}"
    });
    section.appendChild(providerExtraField);

    get("/apis").then(function (apis) {
      apiOptions = apis.map(function (item) {
        return { id: item.id, label: item.label, path: item.path, detail: item.detail };
      });
      apiSelect.setOptions(apiOptions, provider.api);
      showApiHint();
    }).catch(function (error) {
      apiHint.textContent = error.message;
      apiHint.classList.add("error");
    });

    function showApiHint() {
      var id = apiSelect.getValue();
      var found = apiOptions.filter(function (item) { return item.id === id; })[0];
      if (!found) {
        apiHint.textContent = "";
        return;
      }
      apiHint.textContent = found.detail + " 实际请求 " + (provider.baseUrl || "{地址}")
        .replace(/\/+$/, "") + found.path;
    }
    apiSelect.addEventListener("change", showApiHint);

    var actions = el("div", "actions");
    var save = button("保存覆盖", { tone: "primary" });
    var clearOverlay = button("清除覆盖", {
      onClick: function () {
        confirmDialog({
          title: "清除覆盖",
          message: "清除后，该供应商的连接信息回到配置文件里的值。已保存的地址、密钥和超时会从数据库删除。",
          confirmText: "清除"
        }).then(function (ok) {
          if (!ok) {
            return;
          }
          post("/providers/" + enc(provider.name) + "/overlay/clear", {})
            .then(function () {
              toast("已清除覆盖，连接信息回到配置文件。", "ok");
              loadProviders();
            }).catch(function (error) {
              toast(error.message, "error");
            });
        });
      }
    });
    var probe = button("测试连通", {});
    var sync = button("同步模型", {});
    actions.appendChild(save);
    actions.appendChild(clearOverlay);
    actions.appendChild(probe);
    actions.appendChild(sync);
    section.appendChild(actions);

    var report = statusLine(section);

    save.addEventListener("click", function () {
      save.disabled = true;
      report("正在保存…", "info");
      var providerExtraValue = providerExtraField.getValue();
      if (providerExtraValue === undefined) {
        save.disabled = false;
        report("附加请求体不是合法 JSON 对象，请修正后再保存。", "error");
        return;
      }
      post("/providers/" + enc(provider.name) + "/overlay", {
        api: apiSelect.getValue(),
        baseUrl: baseUrl.value,
        apiKey: apiKey.value,
        connectTimeoutMs: connect.value,
        readTimeoutMs: read.value,
        providerHeaders: providerHeadersField.getValue(),
        providerExtraBody: providerExtraValue
      }).then(function () {
        toast("覆盖已写入数据库。", "ok");
        loadProviders();
      }).catch(function (error) {
        save.disabled = false;
        report(error.message, "error");
      });
    });

    probe.addEventListener("click", function () {
      probe.disabled = true;
      report("正在请求 /models 探测连通…", "info");
      post("/providers/" + enc(provider.name) + "/connectivity", {})
        .then(function (data) {
          probe.disabled = false;
          report((data.ok ? "连通成功" : "连通失败")
            + "，HTTP " + data.httpStatus + "，耗时 " + data.latencyMs + " 毫秒。"
            + (data.message || ""), data.ok ? "ok" : "error");
        }).catch(function (error) {
          probe.disabled = false;
          report(error.message, "error");
        });
    });

    sync.addEventListener("click", function () {
      sync.disabled = true;
      report("正在读取远端模型列表…", "info");
      post("/providers/" + enc(provider.name) + "/models/sync", {})
        .then(function (data) {
          sync.disabled = false;
          report("", "info");
          openSyncDialog(provider, data.models || []);
        }).catch(function (error) {
          sync.disabled = false;
          report(error.message, "error");
        });
    });

    return section;
  }

  function modelsSection(provider) {
    var section = el("div", "section");
    var head = el("div", "section-head");
    head.appendChild(el("h3", "section-title", "模型"));
    var add = button("新增模型", { tone: "primary", size: "sm", icon: "plus" });
    add.addEventListener("click", function () {
      openModelDialog(provider, null);
    });
    head.appendChild(add);
    section.appendChild(head);

    var tableSection = statusLine(section);
    var dumpHost = el("div");
    var models = provider.models || [];
    if (!models.length) {
      section.appendChild(emptyState("还没有模型",
        "点「同步模型」从供应商读取，或手动添加一个。新加入的模型默认未就绪，补齐能力参数并打开就绪后才能被业务绑定。"));
      section.appendChild(dumpHost);
      return section;
    }

    var wrap = el("div", "table-wrap");
    var table = el("table", "table");
    var thead = el("thead");
    thead.appendChild(headerRow(["模型", "就绪", "推理", "输入", "上下文", "输出上限",
      "思考格式", "操作"]));
    table.appendChild(thead);
    var tbody = el("tbody");
    table.appendChild(tbody);
    wrap.appendChild(table);
    section.appendChild(wrap);

    models.forEach(function (model) {
      tbody.appendChild(modelRow(provider, model, tableSection, dumpHost));
    });

    section.appendChild(dumpHost);
    return section;
  }

  function headerRow(values) {
    var row = el("tr");
    values.forEach(function (value) {
      row.appendChild(el("th", null, value));
    });
    return row;
  }

  function modelRow(provider, model, tableStatus, dumpHost) {
    var row = el("tr");

    var nameCell = el("td", "cell-name");
    var primary = el("div", "name-primary", model.displayName || model.modelId);
    primary.title = model.displayName || model.modelId;
    nameCell.appendChild(primary);
    if (model.displayName && model.displayName !== model.modelId) {
      var secondary = el("div", "mono-text name-secondary", model.modelId);
      secondary.title = model.modelId;
      nameCell.appendChild(secondary);
    }
    if (model.duplicate) {
      nameCell.appendChild(badge("重复 id", "warn"));
    }
    row.appendChild(nameCell);

    // 就绪：表格里的快捷开关，点一下立刻写库并回读。
    var readyCell = el("td", "cell-tight");
    var readySwitch = switchControl(model.ready, {
      title: model.ready ? "点击停用" : "点击启用",
      onChange: function (next) {
        readySwitch.setBusy(true);
        put("/providers/" + enc(provider.name) + "/models/" + enc(model.modelId) + "/ready",
          { ready: next })
          .then(function (updated) {
            readySwitch.setBusy(false);
            readySwitch.setValue(updated.ready);
            tableStatus(next
              ? "已启用 " + model.modelId + "，现在可以被业务绑定。"
              : "已停用 " + model.modelId + "。已有绑定保留，但下一次调用会失败。",
              next ? "ok" : "info");
            toast(next ? "已启用 " + model.modelId : "已停用 " + model.modelId,
              next ? "ok" : "info");
            // 只更新本行显示，避免整页刷新把滚动位置打乱
            readyBadge.textContent = "";
            readyBadge.appendChild(badge(next ? "可用" : "未就绪", next ? "ready" : "off"));
            provider.models.forEach(function (item) {
              if (item.modelId === model.modelId) {
                item.ready = updated.ready;
              }
            });
          }).catch(function (error) {
            readySwitch.setBusy(false);
            readySwitch.setValue(model.ready);
            tableStatus(error.message, "error");
            toast(error.message, "error");
          });
      }
    });
    readyCell.appendChild(readySwitch);
    row.appendChild(readyCell);

    var readyBadge = el("span", "sr-only");
    readyCell.appendChild(readyBadge);

    row.appendChild(el("td", null, model.reasoning ? "是" : "否"));
    var modes = (model.inputModes && model.inputModes.length) ? model.inputModes : ["text"];
    row.appendChild(el("td", "mono-text", modes.join(" / ")));
    row.appendChild(el("td", null, model.contextWindow == null ? "—" : String(model.contextWindow)));
    row.appendChild(el("td", null, model.maxTokens == null ? "—" : String(model.maxTokens)));
    row.appendChild(el("td", "mono-text", model.thinkingFormat || "—"));

    var actionsCell = el("td", "cell-actions");
    var rowActions = el("div", "row-actions");
    var edit = button("编辑", {
      size: "sm", title: "修改能力参数与就绪状态",
      onClick: function () { openModelDialog(provider, model); }
    });
    var tryBtn = button("测试", {
      size: "sm", title: "用当前配置发一次测试请求",
      onClick: function () { openTryDialog(provider, model, tableStatus, dumpHost); }
    });
    var remove = button("删除", {
      size: "sm", tone: "danger", title: "从本地列表删除这个模型",
      onClick: function () {
        confirmDialog({
          title: "删除模型",
          message: "删除 " + model.modelId + "？同步不会自动删模型，只有这里能删。仍被业务绑定时会被拒绝。",
          confirmText: "删除",
          danger: true
        }).then(function (ok) {
          if (!ok) {
            return;
          }
          del("/providers/" + enc(provider.name) + "/models/" + enc(model.modelId))
            .then(function () {
              toast("已删除 " + model.modelId, "ok");
              loadProviders();
            }).catch(function (error) {
              toast(error.message, "error");
            });
        });
      }
    });
    rowActions.appendChild(edit);
    rowActions.appendChild(tryBtn);
    rowActions.appendChild(remove);
    actionsCell.appendChild(rowActions);
    row.appendChild(actionsCell);
    return row;
  }

  /* ---------------- 模型编辑弹窗 ---------------- */

  function openModelDialog(provider, model) {
    var isEdit = !!model;
    var body = el("div", "section");
    var states = { ready: model ? !!model.ready : false };

    var idField = textInput(model ? model.modelId : "", {
      placeholder: "供应商那边的模型 id",
      readOnly: isEdit
    });
    body.appendChild(field("模型 id", idField, isEdit
      ? "id 是绑定与请求的依据，创建后不能改。要改名请删掉重建。"
      : "必须与供应商返回的 id 完全一致。"));

    var displayName = textInput(model && model.displayName ? model.displayName : "", {
      placeholder: "留空则显示模型 id"
    });
    body.appendChild(field("显示名", displayName, "只影响页面展示，不参与模型请求。"));

    if (isEdit) {
      body.appendChild(fieldRow("就绪",
        "打开后业务才能绑定这个模型。停用不会解除已有绑定，但下一次调用会失败。",
        switchControl(states.ready, {
          title: "就绪开关",
          onChange: function (next) { states.ready = next; }
        })));
    } else {
      body.appendChild(fieldRow("就绪",
        "新模型默认未就绪。保存后在列表里打开开关即可启用。",
        switchControl(false, { title: "就绪开关", onChange: function () {} })));
    }

    var reasoningSwitch = switchControl(model ? !!model.reasoning : true, {
      title: "推理开关"
    });
    body.appendChild(fieldRow("支持推理", "模型是否会输出思考过程或接受思考档参数。",
      reasoningSwitch));

    var modes = checkboxList(INPUT_MODES,
      (model && model.inputModes && model.inputModes.length) ? model.inputModes : ["text"],
      function () {});
    body.appendChild(field("输入类型", modes,
      "勾选「图片」后，调用方才能给这个模型发图片消息。"));

    var grid = el("div", "form-grid");
    var contextWindow = textInput(model && model.contextWindow != null ? model.contextWindow : 32768,
      { type: "number", min: 0 });
    var maxTokens = textInput(model && model.maxTokens != null ? model.maxTokens : 4096,
      { type: "number", min: 1 });
    grid.appendChild(field("上下文窗口", contextWindow, "0 表示未知"));
    grid.appendChild(field("输出上限", maxTokens, "单次回复最多多少 token"));
    body.appendChild(grid);

    var formatOptions = [{ id: "", label: "正在读取…" }];
    var formatSelect = select(formatOptions, model ? model.thinkingFormat : null, function () {
      showFormatHelp();
    });
    var formatHint = el("p", "field-hint", "思考格式决定请求体里怎么写思考开关。");
    var formatField = el("div", "field");
    formatField.appendChild(el("span", "field-label", "思考格式"));
    formatField.appendChild(formatSelect);
    formatField.appendChild(formatHint);
    body.appendChild(formatField);

    function showFormatHelp() {
      var id = formatSelect.getValue();
      var found = formatOptions.filter(function (item) { return item.id === id; })[0];
      formatHint.textContent = found && found.desc
        ? found.desc : "思考格式决定请求体里怎么写思考开关。";
    }

    get("/thinking-formats").then(function (formats) {
      formatOptions = formats.map(function (item) {
        return { id: item.id, label: item.label, desc: item.detail };
      });
      // 必须先替换选项再设值：占位选项里没有真实 id，直接 setValue 会落空。
      formatSelect.setOptions(formatOptions,
        model ? model.thinkingFormat : (formatOptions[0] || {}).id);
      showFormatHelp();
    }).catch(function (error) {
      formatHint.textContent = error.message;
      formatHint.classList.add("error");
    });

    /* ---------------- 请求参数（高级） ---------------- */

    // 思考档 → 供应商档。右列留空表示「这一档不支持」，对应 JSON 里的 null。
    var levelRows = editableRows({
      label: "思考档映射",
      hint: "左列是本库的思考档，右列是发给供应商的值。右列留空表示这个模型不支持该档。"
        + "不填任何行时，沿用配置文件与库内默认：只会把 xhigh/max 降到 high。",
      keyOptions: THINKING_LEVEL_OPTIONS,
      valuePlaceholder: "留空 = 不支持",
      initial: mapToRows(model && model.thinkingLevelMap)
    });
    body.appendChild(levelRows);

    var modelHeaders = editableRows({
      label: "模型请求头",
      hint: "只对这个模型生效。与供应商级同名（忽略大小写）时，这里的值覆盖供应商级。"
        + "Authorization 与 Content-Type 由库管理，不能在这里配。",
      keyPlaceholder: "头名，如 X-Model-Version",
      valuePlaceholder: "头值",
      initial: mapToRows(model && model.modelHeaders)
    });
    body.appendChild(modelHeaders);

    var samplerParams = jsonField({
      label: "采样参数",
      hint: "合并进请求体的默认采样参数，例如 {\"temperature\": 0.2}。"
        + "不填表示沿用配置文件。",
      value: jsonText(model && model.samplerParams),
      placeholder: "{\"temperature\": 0.7}"
    });
    body.appendChild(samplerParams);

    var samplerByLevel = jsonField({
      label: "分档采样参数",
      hint: "按生效思考档再叠一层，例如 {\"high\": {\"top_p\": 0.95}}。会覆盖上面的同名键。",
      value: jsonText(model && model.samplerParamsByLevel),
      placeholder: "{\"high\": {\"top_p\": 0.95}}"
    });
    body.appendChild(samplerByLevel);

    var extraBody = jsonField({
      label: "附加请求体",
      hint: "供应商私有字段，深合并进请求体，例如 {\"chat_template_kwargs\": {\"enable_thinking\": true}}。",
      value: jsonText(model && model.extraBody),
      placeholder: "{\"vendor_field\": \"value\"}"
    });
    body.appendChild(extraBody);

    var preview = el("div", "request-preview");
    var previewHead = el("div", "json-head");
    var previewToggle = button("这次会发什么", { size: "sm" });
    var previewRefresh = button("刷新", { size: "sm", icon: "refresh" });
    previewRefresh.hidden = true;
    previewHead.appendChild(previewToggle);
    previewHead.appendChild(previewRefresh);
    var previewBody = el("div", "request-preview-body");
    previewBody.hidden = true;
    previewToggle.addEventListener("click", function () {
      previewBody.hidden = !previewBody.hidden;
      previewRefresh.hidden = previewBody.hidden;
      if (!previewBody.hidden) {
        renderPreview();
      }
    });
    previewRefresh.addEventListener("click", renderPreview);
    preview.appendChild(previewHead);
    preview.appendChild(previewBody);
    body.appendChild(preview);

    /**
     * 渲染「合并后的请求头与附加字段」预览。只列键与来源，不显示具体的值。
     */
    function renderPreview() {
      previewBody.innerHTML = "";
      var merged = {};
      (provider.providerHeaders ? Object.keys(provider.providerHeaders) : [])
        .forEach(function (key) { merged[key.toLowerCase()] = { name: key, from: "供应商级" }; });
      var modelHeads = modelHeaders.getValue();
      Object.keys(modelHeads).forEach(function (key) {
        merged[key.toLowerCase()] = { name: key, from: "模型级" };
      });
      var list = el("div", "kv-grid");
      if (!Object.keys(merged).length) {
        list.appendChild(el("span", "kv-key", "请求头"));
        list.appendChild(el("span", "kv-value", "只有 Content-Type 与 Authorization"));
      } else {
        Object.keys(merged).forEach(function (key) {
          list.appendChild(el("span", "kv-key", merged[key].name));
          list.appendChild(el("span", "kv-value", "来自" + merged[key].from));
        });
      }
      previewBody.appendChild(el("p", "field-hint", "请求头（键名与来源）："));
      previewBody.appendChild(list);

      var sampler = samplerParams.getValue();
      if (sampler === undefined) {
        previewBody.appendChild(el("p", "field-hint error", "采样参数 JSON 有语法错误，请先修正。"));
        return;
      }
      var extra = extraBody.getValue();
      if (extra === undefined) {
        previewBody.appendChild(el("p", "field-hint error", "附加请求体 JSON 有语法错误，请先修正。"));
        return;
      }
      var keys = Object.keys(sampler).concat(Object.keys(extra));
      previewBody.appendChild(el("p", "field-hint",
        keys.length ? "请求体附加字段：" + keys.join("、") : "请求体附加字段：无"));
    }

    modelHeaders.addEventListener("change", function () {
      if (!previewBody.hidden) {
        renderPreview();
      }
    });

    var modal = openModal({
      title: isEdit ? "修改模型" : "新增模型",
      desc: isEdit ? model.modelId : "先创建一条未就绪记录，再补齐能力参数。",      body: body,
      onMount: function (dialog) {
        dialog.addAction(button("取消", { onClick: dialog.close }));
        dialog.addAction(button(isEdit ? "保存" : "创建", {
          tone: "primary",
          onClick: function () { save(); }
        }));
      }
    });
    var saveBtn = modal.footer.lastChild;
    var report = statusLine(body);
    report("", "info");

    function save() {
      var modelId = (model ? model.modelId : idField.value).trim();
      if (!modelId) {
        report("请填写模型 id。", "error");
        idField.focus();
        return;
      }
      var contextValue = Number(contextWindow.value);
      var maxValue = Number(maxTokens.value);
      if (!isFinite(contextValue) || contextValue < 0) {
        report("上下文窗口必须是不小于 0 的整数。", "error");
        return;
      }
      if (!isFinite(maxValue) || maxValue <= 0) {
        report("输出上限必须是大于 0 的整数。", "error");
        return;
      }
      var format = formatSelect.getValue();
      if (!format) {
        report("请选择思考格式。", "error");
        return;
      }
      var samplerValue = samplerParams.getValue();
      if (samplerValue === undefined) {
        report("采样参数不是合法 JSON 对象，请修正后再保存。", "error");
        return;
      }
      var byLevelValue = samplerByLevel.getValue();
      if (byLevelValue === undefined) {
        report("分档采样参数不是合法 JSON 对象，请修正后再保存。", "error");
        return;
      }
      var extraValue = extraBody.getValue();
      if (extraValue === undefined) {
        report("附加请求体不是合法 JSON 对象，请修正后再保存。", "error");
        return;
      }
      var payload = {
        reasoning: reasoningSwitch.getValue(),
        inputModes: modes.getValue(),
        contextWindow: contextValue,
        maxTokens: maxValue,
        thinkingFormat: format,
        displayName: displayName.value,
        // 思考档映射右列留空 = 不支持（JSON 里的 null），所以用允许 null 的取法。
        thinkingLevelMap: levelRows.getValueAllowingNull(),
        samplerParams: samplerValue,
        samplerParamsByLevel: byLevelValue,
        modelHeaders: modelHeaders.getValue(),
        extraBody: extraValue
      };
      saveBtn.disabled = true;
      report("正在保存…", "info");
      var base = "/providers/" + enc(provider.name) + "/models";
      var create = isEdit ? Promise.resolve() : post(base, { modelId: modelId });
      create.then(function () {
        return put(base + "/" + enc(modelId), payload);
      }).then(function () {
        if (isEdit && states.ready !== !!model.ready) {
          return put(base + "/" + enc(modelId) + "/ready", { ready: states.ready });
        }
      }).then(function () {
        modal.close();
        toast(isEdit ? "模型已更新。" : "模型已创建。", "ok");
        loadProviders();
      }).catch(function (error) {
        saveBtn.disabled = false;
        report(error.message, "error");
      });
    }
  }

  /* ---------------- 同步与测试调用 ---------------- */

  function openSyncDialog(provider, models) {
    var body = el("div", "section");
    body.appendChild(el("p", "card-desc",
      "勾选要加入本地列表的模型。已在列表里的保持原样，本次没返回的也不会被删除。"));

    if (!models.length) {
      body.appendChild(emptyState("远端没有返回模型",
        "检查供应商的 /models 接口是否可用，或手动添加模型 id。"));
    }
    var selected = {};
    var list = el("div", "checkbox-list");
    models.forEach(function (model) {
      var node = el("button", "checkbox");
      node.type = "button";
      node.setAttribute("role", "checkbox");
      var stored = !!model.stored;
      selected[model.modelId] = !stored;
      node.setAttribute("aria-checked", (!stored) ? "true" : "false");
      if (stored) {
        node.disabled = true;
      }
      var box = el("span", "checkbox-box");
      box.appendChild(icon("check"));
      node.appendChild(box);
      node.appendChild(el("span", null, model.modelId + (stored ? "（已在列表中）" : "")));
      node.addEventListener("click", function () {
        if (stored) {
          return;
        }
        var next = !selected[model.modelId];
        selected[model.modelId] = next;
        node.setAttribute("aria-checked", next ? "true" : "false");
      });
      list.appendChild(node);
    });
    body.appendChild(list);

    openModal({
      title: "选择要加入的模型",
      desc: "远端返回 " + models.length + " 个模型。",
      body: body,
      onMount: function (dialog) {
        dialog.addAction(button("取消", { onClick: dialog.close }));
        dialog.addAction(button("加入列表", {
          tone: "primary",
          onClick: function () {
            var ids = Object.keys(selected).filter(function (id) {
              return selected[id];
            });
            if (!ids.length) {
              toast("没有勾选新的模型。", "info");
              dialog.close();
              return;
            }
            post("/providers/" + enc(provider.name) + "/models/adopt", { modelIds: ids })
              .then(function (data) {
                dialog.close();
                toast("已加入 " + data.added + " 个模型，默认未就绪。", "ok");
                loadProviders();
              }).catch(function (error) {
                toast(error.message, "error");
              });
          }
        }));
      }
    });
  }

  function openTryDialog(provider, model, tableStatus, dumpHost) {
    var body = el("div", "section");
    var levelSelect = select(THINKING_LEVEL_IDS.map(function (level) {
      return { id: level, label: level };
    }), "off", function () {});
    body.appendChild(field("思考档", levelSelect,
      "测试调用会带上这个思考档；off 表示关闭思考。"));
    var resultHost = el("div");
    body.appendChild(resultHost);

    var modal = openModal({
      title: "测试调用 " + model.modelId,
      desc: "用当前生效的地址和密钥发一次短请求，不写调用日志。",
      wide: true,
      body: body,
      onMount: function (dialog) {
        dialog.addAction(button("关闭", { onClick: dialog.close }));
        dialog.addAction(button("发起调用", {
          tone: "primary",
          onClick: function () {
            clear(resultHost);
            resultHost.appendChild(el("p", "status-line info", "正在调用…"));
            post("/providers/" + enc(provider.name) + "/models/" + enc(model.modelId) + "/try",
              { thinkingLevel: levelSelect.getValue() })
              .then(function (data) {
                renderTryResult(resultHost, data);
                tableStatus((data.ok ? "调用成功" : "调用失败") + "，"
                  + data.latencyMs + " 毫秒。", data.ok ? "ok" : "error");
                showTryDump(dumpHost, data);
              }).catch(function (error) {
                clear(resultHost);
                resultHost.appendChild(el("p", "status-line error", error.message));
              });
          }
        }));
      }
    });
    return modal;
  }

  function renderTryResult(host, data) {
    clear(host);
    var kv = el("div", "kv-grid");
    addKv(kv, "结果", data.ok ? "成功" : "失败");
    addKv(kv, "HTTP", String(data.httpStatus));
    addKv(kv, "耗时", data.latencyMs + " 毫秒");
    if (data.message) {
      addKv(kv, "说明", data.message);
    }
    host.appendChild(kv);
    if (data.content) {
      host.appendChild(el("p", "field-label", "回复"));
      var reply = el("pre", "code-block");
      reply.textContent = data.content;
      host.appendChild(reply);
    }
  }

  function addKv(grid, key, value) {
    grid.appendChild(el("span", "kv-key", key));
    grid.appendChild(el("span", "kv-value", value));
  }

  function showTryDump(host, data) {
    clear(host);
    var details = el("div", "section");
    details.appendChild(el("h3", "section-title", "最近一次测试调用的报文"));
    details.appendChild(el("p", "field-label", "发给模型的请求"));
    var req = el("pre", "code-block");
    req.textContent = data.request || "（空）";
    details.appendChild(req);
    details.appendChild(el("p", "field-label", "模型原始响应"));
    var res = el("pre", "code-block");
    res.textContent = data.response || "（空）";
    details.appendChild(res);
    host.appendChild(details);
  }

  /* ---------------- 业务与提示词 ---------------- */

  function loadBiz() {
    var panel = document.getElementById("biz");
    clear(panel);
    panel.appendChild(el("p", "status-line", "正在读取业务…"));
    Promise.all([get("/biz"), get("/providers")]).then(function (result) {
      var bizList = result[0];
      var providers = result[1];
      clear(panel);
      if (!bizList.length) {
        panel.appendChild(emptyState("配置文件里还没有业务 id",
          "在 application.yml 的 ai-lite.llm.biz-ids 里加一行，重启后就会出现在这里。"));
        return;
      }
      var ready = [];
      providers.forEach(function (provider) {
        (provider.models || []).forEach(function (model) {
          if (model.ready) {
            ready.push({
              providerId: provider.name,
              modelId: model.modelId,
              displayName: model.displayName || model.modelId
            });
          }
        });
      });
      bizList.forEach(function (biz) {
        panel.appendChild(bizCard(biz, ready));
      });
    }).catch(function (error) {
      clear(panel);
      panel.appendChild(emptyState("读取业务失败", error.message,
        button("重试", { tone: "primary", onClick: loadBiz })));
    });
  }

  /** 业务卡片的展开状态。保存绑定后会整页重载，靠它把卡片保持在展开。 */
  var expandedBiz = {};

  /** 「操作记录」的展开状态，按业务 id 记，默认收起。 */
  var expandedAudit = {};

  function bizCard(biz, ready) {
    var card = el("section", "card");
    var header = el("button", "disclosure");
    header.type = "button";
    var headMain = el("div", "disclosure-main");
    headMain.appendChild(el("span", "disclosure-name", biz.bizId));
    var desc = el("p", "card-desc");
    desc.textContent = biz.promptActive ? "已有生效提示词。" : "还没有生效提示词。";
    headMain.appendChild(desc);
    var headBadges = el("div", "disclosure-meta");
    headBadges.appendChild(badge(biz.binding
      ? (biz.binding.providerId + " / " + biz.binding.modelId) : "未绑定模型",
      biz.binding ? "info" : "off"));
    if (biz.promptActive) {
      headBadges.appendChild(badge("提示词已生效", "ready"));
    }
    headMain.appendChild(headBadges);
    header.appendChild(headMain);
    header.appendChild(icon("chevron", "chevron"));
    card.appendChild(header);

    var body = el("div", "disclosure-body");
    var open = !!expandedBiz[biz.bizId];
    body.hidden = !open;
    header.setAttribute("aria-expanded", open ? "true" : "false");
    header.addEventListener("click", function () {
      var next = body.hidden;
      body.hidden = !next;
      expandedBiz[biz.bizId] = next;
      header.setAttribute("aria-expanded", next ? "true" : "false");
    });
    card.appendChild(body);

    var content = el("div");
    content.style.display = "flex";
    content.style.flexDirection = "column";
    content.style.gap = "18px";
    content.appendChild(bindingSection(biz, ready, desc));
    content.appendChild(promptSection(biz));
    body.appendChild(content);
    return card;
  }

  function bindingSection(biz, ready, bizNote) {
    var section = el("div", "section");
    section.appendChild(el("h3", "section-title", "模型绑定"));

    var options = ready.map(function (item) {
      return {
        id: item.providerId + "\n" + item.modelId,
        label: item.displayName,
        desc: item.providerId + " · " + item.modelId
      };
    });
    var currentKey = biz.binding
      ? biz.binding.providerId + "\n" + biz.binding.modelId : null;
    var modelSelect = select(options, currentKey, function () {});
    var levelSelect = select(THINKING_LEVEL_IDS.map(function (level) {
      return { id: level, label: level };
    }), biz.binding ? biz.binding.thinkingLevel : "off", function () {});

    var grid = el("div", "form-grid");
    grid.appendChild(field("模型", modelSelect, ready.length
      ? "只列出已就绪的模型。" : "还没有已就绪的模型，先去供应商页启用一个。"));
    grid.appendChild(field("思考档", levelSelect));
    section.appendChild(grid);

    var actions = el("div", "actions");
    var save = button("保存绑定", { tone: "primary" });
    var clearBind = button("清除绑定", {});
    actions.appendChild(save);
    actions.appendChild(clearBind);
    section.appendChild(actions);
    var report = statusLine(section);

    save.addEventListener("click", function () {
      var key = modelSelect.getValue();
      if (!key) {
        report("请选择一个已就绪的模型。", "error");
        return;
      }
      var parts = key.split("\n");
      save.disabled = true;
      report("正在保存…", "info");
      put("/biz/" + enc(biz.bizId), {
        providerId: parts[0],
        modelId: parts[1],
        thinkingLevel: levelSelect.getValue()
      }).then(function () {
        save.disabled = false;
        report("模型绑定已保存，下一次调用就会使用它。", "ok");
        toast("绑定已保存。", "ok");
        bizNote.textContent = biz.promptActive ? "已有生效提示词。" : "还没有生效提示词。";
        loadBiz();
      }).catch(function (error) {
        save.disabled = false;
        report(error.message, "error");
      });
    });

    clearBind.addEventListener("click", function () {
      confirmDialog({
        title: "清除绑定",
        message: "清除 " + biz.bizId + " 的模型绑定？业务 id 仍留在白名单，但这个业务将无法解析到模型。",
        confirmText: "清除",
        danger: true
      }).then(function (ok) {
        if (!ok) {
          return;
        }
        del("/biz/" + enc(biz.bizId) + "/binding").then(function () {
          toast("绑定已清除。", "ok");
          loadBiz();
        }).catch(function (error) {
          report(error.message, "error");
        });
      });
    });

    return section;
  }

  function promptSection(biz) {
    var section = el("div", "section");
    var head = el("div", "section-head");
    head.appendChild(el("h3", "section-title", "提示词"));
    var newDraft = button("新建草稿", { tone: "primary", size: "sm", icon: "plus" });
    head.appendChild(newDraft);
    section.appendChild(head);
    section.appendChild(el("p", "card-desc",
      "内容不在这里常驻显示。点某一版的「查看」看具体模板，"
      + "点草稿的「修改」打开编辑弹窗，改完保存即覆盖那一版。"));

    var historyTable = el("table", "table");
    var historyWrap = el("div", "table-wrap");
    historyWrap.appendChild(historyTable);
    section.appendChild(historyWrap);

    var report = statusLine(section);

    var auditTable = el("table", "table");
    var auditWrap = el("div", "table-wrap");
    auditWrap.appendChild(auditTable);
    /*
     * 操作记录默认收起：它是排查时才看的辅助信息，常驻展开会把版本列表挤下去。
     * 复用 disclosure 的样式，展开状态按业务 id 记住，刷新后不跳回收起。
     */
    var auditHost = el("div", "audit-block");
    var auditToggle = el("button", "disclosure audit-toggle");
    auditToggle.type = "button";
    var auditLabel = el("span", "disclosure-main");
    auditLabel.appendChild(el("span", "disclosure-name", "操作记录"));
    var auditCount = el("span", "disclosure-meta");
    auditToggle.appendChild(auditLabel);
    auditToggle.appendChild(auditCount);
    auditToggle.appendChild(icon("chevron", "chevron"));
    auditHost.appendChild(auditToggle);

    var auditBody = el("div", "disclosure-body");
    auditBody.appendChild(auditWrap);
    auditHost.appendChild(auditBody);

    function applyAuditOpen(open) {
      auditBody.hidden = !open;
      auditToggle.setAttribute("aria-expanded", open ? "true" : "false");
      expandedAudit[biz.bizId] = open;
    }
    applyAuditOpen(!!expandedAudit[biz.bizId]);
    auditToggle.addEventListener("click", function () {
      applyAuditOpen(auditBody.hidden);
    });
    section.appendChild(auditHost);

    function statusLabel(value) {
      if (value === "published") { return "生效"; }
      if (value === "retired") { return "已退役"; }
      if (value === "draft") { return "草稿"; }
      return value || "";
    }

    function statusTone(value) {
      if (value === "published") { return "ready"; }
      if (value === "draft") { return "warn"; }
      return "off";
    }

    function formatTime(ms) {
      if (ms == null) { return ""; }
      var date = new Date(Number(ms));
      if (isNaN(date.getTime())) { return ""; }
      function pad(n) { return n < 10 ? "0" + n : String(n); }
      return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate())
        + " " + pad(date.getHours()) + ":" + pad(date.getMinutes());
    }

    function reload() {
      return get("/biz/" + enc(biz.bizId) + "/prompt").then(function (prompt) {
        renderVersions(prompt.versions || []);
        renderAudits(prompt.audits || []);
        return prompt;
      });
    }

    /* ---------- 版本列表 ---------- */

    function renderVersions(versions) {
      clear(historyTable);
      var thead = el("thead");
      thead.appendChild(headerRow(["版本", "状态", "创建时间", "操作人", "操作"]));
      historyTable.appendChild(thead);
      var tbody = el("tbody");
      historyTable.appendChild(tbody);
      if (!versions || !versions.length) {
        var row = el("tr");
        var cell = el("td", null, "还没有版本记录。点右上角「新建草稿」开始。");
        cell.colSpan = 5;
        cell.className = "muted cell-empty";
        row.appendChild(cell);
        tbody.appendChild(row);
        return;
      }
      versions.forEach(function (item) {
        var row = el("tr");
        row.appendChild(el("td", "mono-text", "v" + item.versionNo));
        var statusCell = el("td");
        statusCell.appendChild(badge(statusLabel(item.status), statusTone(item.status)));
        row.appendChild(statusCell);
        row.appendChild(el("td", null, formatTime(item.createTime)));
        row.appendChild(el("td", null, item.createdBy || "—"));

        var actionsCell = el("td", "cell-actions");
        var rowActions = el("div", "row-actions");
        rowActions.appendChild(button("查看", {
          size: "sm",
          onClick: function () { openViewDialog(item); }
        }));
        if (item.status === "draft") {
          rowActions.appendChild(button("修改", {
            size: "sm",
            onClick: function () { openEditDialog(item); }
          }));
          rowActions.appendChild(button("发布", {
            size: "sm", tone: "primary",
            onClick: function () {
              post("/biz/" + enc(biz.bizId) + "/prompt/publish", { versionId: item.versionId })
                .then(function () {
                  report("已发布 v" + item.versionNo + "。", "ok");
                  toast("提示词已发布。", "ok");
                  return reload();
                }).catch(function (error) {
                  report(error.message, "error");
                });
            }
          }));
        } else if (item.status === "retired") {
          rowActions.appendChild(button("回滚生效", {
            size: "sm",
            onClick: function () {
              confirmDialog({
                title: "回滚提示词",
                message: "把 v" + item.versionNo + " 重新设为生效？当前生效的版本会退役。",
                confirmText: "回滚"
              }).then(function (ok) {
                if (!ok) { return; }
                post("/biz/" + enc(biz.bizId) + "/prompt/rollback", { versionId: item.versionId })
                  .then(function () {
                    report("已回滚到 v" + item.versionNo + "。", "ok");
                    toast("已回滚。", "ok");
                    return reload();
                  }).catch(function (error) {
                    report(error.message, "error");
                  });
              });
            }
          }));
        }
        actionsCell.appendChild(rowActions);
        row.appendChild(actionsCell);
        tbody.appendChild(row);
      });
    }

    function renderAudits(audits) {
      clear(auditTable);
      clear(auditCount);
      auditCount.appendChild(badge("共 " + ((audits && audits.length) || 0) + " 条"));
      var thead = el("thead");
      thead.appendChild(headerRow(["操作", "详情", "操作人", "时间"]));
      auditTable.appendChild(thead);
      var tbody = el("tbody");
      auditTable.appendChild(tbody);
      if (!audits || !audits.length) {
        var row = el("tr");
        var cell = el("td", null, "还没有操作记录。");
        cell.colSpan = 4;
        cell.className = "muted cell-empty";
        row.appendChild(cell);
        tbody.appendChild(row);
        return;
      }
      audits.forEach(function (item) {
        var row = el("tr");
        row.appendChild(el("td", null, item.action || ""));
        row.appendChild(el("td", null, item.detail || ""));
        row.appendChild(el("td", null, item.actor || ""));
        row.appendChild(el("td", null, formatTime(item.createTime)));
        tbody.appendChild(row);
      });
    }

    /* ---------- 模板编辑区（弹窗共用） ---------- */

    /**
     * 建三个模板输入框。查看弹窗用它做只读展示，编辑/新建弹窗用它做输入，
     * 这样三处渲染出来的结构完全一致，不会出现只读态和编辑态长得不一样。
     */
    function templateFields(source, readOnly) {
      var system = textArea(source ? source.systemTemplate : "", {
        tall: true, placeholder: "系统模板，支持 {{变量}} 占位符"
      });
      var user = textArea(source ? source.userTemplate : "", {
        placeholder: "用户模板，支持 {{变量}} 占位符"
      });
      var schema = textArea(source ? source.outputSchema : "", {
        mono: true, placeholder: "可选。结构化输出的 JSON Schema"
      });
      if (readOnly) {
        [system, user, schema].forEach(function (node) {
          node.readOnly = true;
        });
      }
      var wrap = el("div", "section");
      wrap.appendChild(field("系统模板", system));
      wrap.appendChild(field("用户模板", user));
      wrap.appendChild(field("outputSchema", schema));
      wrap.textareas = { system: system, user: user, schema: schema };
      return wrap;
    }

    function renderedPreview(data) {
      var wrap = el("div", "section");
      wrap.appendChild(el("p", "field-label", "系统消息"));
      wrap.appendChild(el("pre", "code-block", data.system || "（空）"));
      wrap.appendChild(el("p", "field-label", "用户消息"));
      wrap.appendChild(el("pre", "code-block", data.user || "（空）"));
      return wrap;
    }

    function readVars(node) {
      var raw = (node.value || "").trim();
      if (!raw) { return {}; }
      var parsed = JSON.parse(raw);
      if (parsed == null || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("预览变量须为 JSON 对象");
      }
      return parsed;
    }

    /**
     * 预览区：输入变量后按当前模板渲染。放在三个弹窗里共用，
     * 因为「改之前先看看渲染结果」在查看和编辑时都成立。
     */
    function attachPreview(body, textareas) {
      var vars = textArea("{}", { mono: true, placeholder: "预览变量，JSON 对象" });
      vars.rows = 3;
      body.appendChild(field("预览变量（JSON）", vars, "只用于本地预览，不会保存。"));
      var host = el("div");
      body.appendChild(host);
      var show = button("预览渲染结果", { size: "sm" });
      var actionRow = el("div", "actions");
      actionRow.appendChild(show);
      body.appendChild(actionRow);
      var note = el("p", "field-hint");
      body.appendChild(note);
      show.addEventListener("click", function () {
        var varsValue;
        try {
          varsValue = readVars(vars);
        } catch (error) {
          note.textContent = error instanceof SyntaxError
            ? "预览变量 JSON 格式无效" : error.message;
          note.classList.add("error");
          return;
        }
        note.classList.remove("error");
        note.textContent = "";
        post("/biz/" + enc(biz.bizId) + "/prompt/preview", {
          systemTemplate: textareas.system.value,
          userTemplate: textareas.user.value,
          vars: varsValue
        }).then(function (data) {
          clear(host);
          host.appendChild(renderedPreview(data));
        }).catch(function (error) {
          note.textContent = error.message;
          note.classList.add("error");
        });
      });
    }

    /* ---------- 查看弹窗（只读） ---------- */

    function openViewDialog(item) {
      var body = el("div", "section");
      var kv = el("div", "kv-grid");
      addKv(kv, "版本", "v" + item.versionNo);
      addKv(kv, "状态", statusLabel(item.status));
      addKv(kv, "创建时间", formatTime(item.createTime));
      addKv(kv, "操作人", item.createdBy || "—");
      body.appendChild(kv);

      var fields = templateFields(item, true);
      body.appendChild(fields);
      attachPreview(body, fields.textareas);

      openModal({
        title: "查看 v" + item.versionNo,
        desc: "只读。要修改请点该版本的「修改」。",
        wide: true,
        body: body,
        onMount: function (dialog) {
          dialog.addAction(button("关闭", { tone: "primary", onClick: dialog.close }));
        }
      });
    }

    /* ---------- 编辑草稿弹窗 ---------- */

    function openEditDialog(item) {
      var body = el("div", "section");
      var fields = templateFields(item, false);
      body.appendChild(fields);
      attachPreview(body, fields.textareas);
      var note = el("p", "field-hint", "保存会覆盖 v" + item.versionNo
        + " 的内容，版本号不变。想保留原内容就点「新建草稿」。");
      body.appendChild(note);

      var dialog = openModal({
        title: "修改 v" + item.versionNo,
        desc: "只改这一份草稿，不会生效。改完要点「发布」才会生效。",
        wide: true,
        body: body
      });
      var save = button("保存草稿", {
        tone: "primary",
        onClick: function () {
          save.disabled = true;
          note.textContent = "正在保存…";
          note.classList.remove("error");
          post("/biz/" + enc(biz.bizId) + "/prompt/draft", {
            versionId: item.versionId,
            systemTemplate: fields.textareas.system.value,
            userTemplate: fields.textareas.user.value,
            outputSchema: fields.textareas.schema.value
          }).then(function () {
            dialog.close();
            toast("草稿已保存，尚未生效。", "ok");
            report("v" + item.versionNo + " 已更新，尚未生效。", "ok");
            return reload();
          }).catch(function (error) {
            save.disabled = false;
            note.textContent = error.message;
            note.classList.add("error");
          });
        }
      });
      dialog.footer.appendChild(button("取消", { onClick: dialog.close }));
      dialog.footer.appendChild(save);
    }

    /* ---------- 新建草稿弹窗 ---------- */

    function openCreateDialog() {
      var body = el("div", "section");
      var head = el("p", "card-desc",
        "系统模板留空会继承当前生效版本；没有生效版本则用配置默认模板。");
      body.appendChild(head);
      var fields = templateFields(null, false);
      body.appendChild(fields);
      attachPreview(body, fields.textareas);
      var note = el("p", "field-hint");
      body.appendChild(note);

      var dialog = openModal({
        title: "新建草稿",
        desc: "会生成一个新的版本号，原有版本保持不变。",
        wide: true,
        body: body
      });
      var create = button("创建草稿", {
        tone: "primary",
        onClick: function () {
          create.disabled = true;
          note.textContent = "正在创建…";
          note.classList.remove("error");
          post("/biz/" + enc(biz.bizId) + "/prompt/draft", {
            systemTemplate: fields.textareas.system.value,
            userTemplate: fields.textareas.user.value,
            outputSchema: fields.textareas.schema.value
          }).then(function (draft) {
            dialog.close();
            toast("已创建草稿 v" + draft.versionNo + "。", "ok");
            report("新草稿 v" + draft.versionNo + " 已创建，尚未生效。", "ok");
            return reload();
          }).catch(function (error) {
            create.disabled = false;
            note.textContent = error.message;
            note.classList.add("error");
          });
        }
      });
      dialog.footer.appendChild(button("取消", { onClick: dialog.close }));
      dialog.footer.appendChild(create);
    }

    newDraft.addEventListener("click", openCreateDialog);

    reload().catch(function (error) {
      report(error.message, "error");
    });

    return section;
  }

  /* ---------------- 调用日志 ---------------- */

  function loadCalls() {
    var panel = document.getElementById("calls");
    clear(panel);

    /*
     * 记录方式只是一行设置，不值得占一张卡片。压成一条工具栏：
     * 标签 + 下拉 + 保存 + 状态，全部同一行。档位解释放在下拉选项的副标题里。
     */
    var levelBar = el("div", "level-bar");
    levelBar.appendChild(el("span", "level-label", "记录方式"));
    var levelSelect = select([
      { id: "none", label: "不记输入输出", desc: "只保留调用摘要" },
      { id: "failure", label: "只记失败调用", desc: "成功调用只记摘要，失败调用另存输入输出" },
      { id: "all", label: "所有调用都记录", desc: "每次调用都保存消息和回复，不含密钥和图片原文" }
    ], "none", null);
    levelBar.appendChild(levelSelect);
    var saveLevel = button("保存", {});
    levelBar.appendChild(saveLevel);
    var levelStatus = el("span", "level-status");
    levelBar.appendChild(levelStatus);
    panel.appendChild(levelBar);

    get("/calls/body-level").then(function (data) {
      levelSelect.setValue(data.level);
    }).catch(function (error) {
      levelStatus.textContent = error.message;
      levelStatus.className = "level-status error";
    });

    saveLevel.addEventListener("click", function () {
      saveLevel.disabled = true;
      put("/calls/body-level", { level: levelSelect.getValue() })
        .then(function () {
          saveLevel.disabled = false;
          levelStatus.textContent = "已保存";
          levelStatus.className = "level-status ok";
          toast("记录方式已保存。", "ok");
        }).catch(function (error) {
          saveLevel.disabled = false;
          levelStatus.textContent = error.message;
          levelStatus.className = "level-status error";
        });
    });

    var listCard = el("section", "card");
    var listBody = el("div", "card-body");
    listBody.style.display = "flex";
    listBody.style.flexDirection = "column";
    listBody.style.gap = "14px";
    listBody.appendChild(el("h2", "card-title", "调用记录"));
    listCard.appendChild(listBody);
    panel.appendChild(listCard);

    var bizFilter = textInput("", { placeholder: "业务 id" });
    var providerFilter = textInput("", { placeholder: "供应商" });
    var modelFilter = textInput("", { placeholder: "模型 id" });
    var successFilter = select([
      { id: "", label: "全部结果" },
      { id: "true", label: "成功" },
      { id: "false", label: "失败" }
    ], "", function () {});
    var limitFilter = textInput("50", { type: "number", min: 1 });
    var filters = el("div", "filters");
    filters.appendChild(field("业务 id", bizFilter));
    filters.appendChild(field("供应商", providerFilter));
    filters.appendChild(field("模型 id", modelFilter));
    filters.appendChild(field("结果", successFilter));
    filters.appendChild(field("每页条数", limitFilter));
    listBody.appendChild(filters);

    var toolbar = el("div", "pager");
    var query = button("查询", { tone: "primary" });
    var pager = el("div", "actions");
    var prevPage = button("上一页", { size: "sm" });
    var nextPage = button("下一页", { size: "sm" });
    var pageInfo = el("span", "page-info", "");
    pager.appendChild(prevPage);
    pager.appendChild(pageInfo);
    pager.appendChild(nextPage);
    toolbar.appendChild(query);
    toolbar.appendChild(pager);
    listBody.appendChild(toolbar);

    var report = statusLine(listBody);
    var tableWrap = el("div", "table-wrap");
    var table = el("table", "table");
    tableWrap.appendChild(table);
    listBody.appendChild(tableWrap);

    var offset = 0;
    var meta = { total: 0, limit: 50, offset: 0 };

    function pageLimit() {
      var value = Number(limitFilter.value || 50);
      if (!isFinite(value) || value < 1) {
        return 50;
      }
      return Math.min(200, Math.floor(value));
    }

    function updatePager() {
      var limit = meta.limit || pageLimit();
      if (!meta.total) {
        pageInfo.textContent = "共 0 条";
      } else {
        pageInfo.textContent = "第 " + (meta.offset + 1) + "–"
          + Math.min(meta.offset + limit, meta.total) + " 条，共 " + meta.total + " 条";
      }
      prevPage.disabled = meta.offset <= 0;
      nextPage.disabled = meta.offset + limit >= meta.total;
    }

    function renderTable(rows) {
      clear(table);
      var thead = el("thead");
      thead.appendChild(headerRow(["时间", "业务", "供应商", "模型", "思考档", "token",
        "结果", "耗时", "操作"]));
      table.appendChild(thead);
      var tbody = el("tbody");
      table.appendChild(tbody);
      if (!rows.length) {
        var row = el("tr");
        var cell = el("td", null, "没有符合条件的记录。");
        cell.colSpan = 9;
        cell.className = "muted cell-empty";
        row.appendChild(cell);
        tbody.appendChild(row);
        return;
      }
      rows.forEach(function (item) {
        var row = el("tr");
        row.appendChild(el("td", null, item.CREATE_TIME || "—"));
        row.appendChild(el("td", null, item.BIZ_ID || "—"));
        row.appendChild(el("td", null, item.PROVIDER || "—"));
        row.appendChild(el("td", "mono-text", item.MODEL_ID || "—"));
        row.appendChild(el("td", "mono-text", item.THINKING_LEVEL || "—"));
        row.appendChild(el("td", null,
          item.TOTAL_TOKENS == null ? "—" : String(item.TOTAL_TOKENS)));
        var ok = item.SUCCESS === 1 || item.SUCCESS === true;
        var resultCell = el("td");
        resultCell.appendChild(badge(ok ? "成功" : "失败", ok ? "ready" : "danger"));
        row.appendChild(resultCell);
        row.appendChild(el("td", null,
          item.LATENCY_MS == null ? "—" : item.LATENCY_MS + " ms"));
        var actionCell = el("td", "cell-actions");
        actionCell.appendChild(button("详情", {
          size: "sm",
          onClick: function () { openCallDetail(item.CALL_ID); }
        }));
        row.appendChild(actionCell);
        tbody.appendChild(row);
      });
    }

    function run() {
      query.disabled = true;
      var limit = pageLimit();
      var path = "/calls?limit=" + enc(String(limit)) + "&offset=" + enc(String(offset));
      if (bizFilter.value) { path += "&bizId=" + enc(bizFilter.value); }
      if (providerFilter.value) { path += "&provider=" + enc(providerFilter.value); }
      if (modelFilter.value) { path += "&modelId=" + enc(modelFilter.value); }
      if (successFilter.getValue()) { path += "&success=" + enc(successFilter.getValue()); }
      get(path).then(function (page) {
        query.disabled = false;
        meta = {
          total: page.total == null ? 0 : Number(page.total),
          limit: page.limit == null ? limit : Number(page.limit),
          offset: page.offset == null ? offset : Number(page.offset)
        };
        offset = meta.offset;
        updatePager();
        renderTable(page.items || []);
        report("", "info");
      }).catch(function (error) {
        query.disabled = false;
        clear(table);
        report(error.message, "error");
      });
    }

    query.addEventListener("click", function () {
      offset = 0;
      run();
    });
    prevPage.addEventListener("click", function () {
      offset = Math.max(0, offset - pageLimit());
      run();
    });
    nextPage.addEventListener("click", function () {
      if (offset + pageLimit() < meta.total) {
        offset += pageLimit();
        run();
      }
    });

    run();
  }

  function openCallDetail(callId) {
    var body = el("div", "section");
    get("/calls/" + enc(callId)).then(function (item) {
      var kv = el("div", "kv-grid");
      addKv(kv, "CALL_ID", item.CALL_ID || callId);
      addKv(kv, "业务", item.BIZ_ID || "—");
      addKv(kv, "供应商", item.PROVIDER || "—");
      addKv(kv, "模型", item.MODEL_ID || "—");
      addKv(kv, "思考档", item.THINKING_LEVEL || "—");
      addKv(kv, "token", item.TOTAL_TOKENS == null ? "—" : String(item.TOTAL_TOKENS));
      addKv(kv, "结果", (item.SUCCESS === 1 || item.SUCCESS === true) ? "成功" : "失败");
      addKv(kv, "耗时", item.LATENCY_MS == null ? "—" : item.LATENCY_MS + " ms");
      addKv(kv, "摘要", item.ERROR_BRIEF || item.STOP_REASON || "—");
      body.appendChild(kv);
      if (item.INPUT_TEXT || item.OUTPUT_TEXT) {
        body.appendChild(el("p", "field-label", "输入"));
        var input = el("pre", "code-block tall");
        input.textContent = item.INPUT_TEXT || "（空）";
        body.appendChild(input);
        body.appendChild(el("p", "field-label", "输出"));
        var output = el("pre", "code-block tall");
        output.textContent = item.OUTPUT_TEXT || "（空）";
        body.appendChild(output);
      }
      openModal({
        title: "调用详情",
        desc: callId,
        wide: true,
        body: body,
        onMount: function (dialog) {
          dialog.addAction(button("关闭", { tone: "primary", onClick: dialog.close }));
        }
      });
    }).catch(function (error) {
      toast(error.message, "error");
    });
  }

  /* ---------------- 启动 ---------------- */

  switchTab("providers");
})();
