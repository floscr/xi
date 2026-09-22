import * as squint_core from 'squint-cljs/core.js';
if (squint_core.truth_(window.__xiDesignActive)) {
} else {
window.__xiDesignActive = true;
if (squint_core.truth_(window.__xiDesignQueue)) {
} else {
window.__xiDesignQueue = []};
let cfg1 = (() => {
const or__23542__auto__1 = window.__XI_DESIGN_CFG__;
if (squint_core.truth_(or__23542__auto__1)) {
return or__23542__auto__1} else {
return ({})};

})();
let C2 = (() => {
const or__23542__auto__2 = cfg1.colors;
if (squint_core.truth_(or__23542__auto__2)) {
return or__23542__auto__2} else {
return ({})};

})();
let doc3 = document;
let root4 = doc3.documentElement;
let sans5 = "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
let mono6 = "ui-monospace, 'SF Mono', Menlo, monospace";
let svg_icon7 = (function (paths, size, color) {
return `${"<svg width=\""}${size??''}${"\" height=\""}${size??''}${"\" viewBox=\"0 0 24 24\" "}${"fill=\"none\" stroke=\""}${color??''}${"\" stroke-width=\"2\" "}${"stroke-linecap=\"round\" stroke-linejoin=\"round\" "}${"style=\"display:inline-block;vertical-align:middle;flex:none;\">"}${paths??''}${"</svg>"}`;

});
let sparkles_path8 = `${"<path d=\"M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962"}${"L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5"}${"A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 "}${"0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z\"/>"}${"<path d=\"M20 3v4\"/><path d=\"M22 5h-4\"/>"}${"<path d=\"M4 17v2\"/><path d=\"M5 18H3\"/>"}`;
let check_path9 = "<path d=\"M20 6 9 17l-5-5\"/>";
let spark10 = svg_icon7(sparkles_path8, 15, C2.accent);
let spark_white11 = svg_icon7(sparkles_path8, 15, "#fff");
let check_ok12 = svg_icon7(check_path9, 16, C2.success);
let state13 = ({"picking": false, "hovered": null, "selected": null, "popover": null, "toastTimer": null});
const esc3 = (function (s) {
return `${s??''}`.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll("\"", "&quot;");

});
const selector4 = (function (el) {
if (squint_core.truth_(squint_core.not_empty(el.id))) {
return `${"#"}${CSS.escape(el.id)??''}`} else {
const path9 = [];
let cur10 = el;
while(true){
if (squint_core.truth_((() => {
const and__23573__auto__11 = cur10;
if (squint_core.truth_(and__23573__auto__11)) {
return (!squint_core._EQ_(cur10, doc3.body) && !squint_core._EQ_(cur10, root4))} else {
return and__23573__auto__11};

})())) {
if (squint_core.truth_(squint_core.not_empty(cur10.id))) {
path9.unshift(`${"#"}${CSS.escape(cur10.id)??''}`)} else {
const tag12 = cur10.tagName.toLowerCase();
const parent13 = cur10.parentElement;
const part14 = ((squint_core.truth_(parent13)) ? ((() => {
const same15 = Array.from(parent13.children).filter((function (c) {
return squint_core._EQ_(c.tagName, cur10.tagName);

}));
if ((same15.length > 1)) {
return `${tag12??''}${":nth-of-type("}${(same15.indexOf(cur10) + 1)??''}${")"}`} else {
return tag12};

})()) : (tag12));
path9.unshift(part14);
let G__16 = cur10.parentElement;
cur10 = G__16;
continue;
}};break;
}
;
const or__23542__auto__17 = squint_core.not_empty(path9.join(" > "));
if (squint_core.truth_(or__23542__auto__17)) {
return or__23542__auto__17} else {
return el.tagName.toLowerCase()};
};

});
const info5 = (function (el) {
const s18 = squint_core.atom(el.tagName.toLowerCase());
if (squint_core.truth_(squint_core.not_empty(el.id))) {
squint_core.reset_BANG_(s18, `${squint_core.deref(s18)??''}${"#"}${el.id??''}`)} else {
if (squint_core.truth_((() => {
const and__23573__auto__19 = el.className;
if (squint_core.truth_(and__23573__auto__19)) {
return squint_core.string_QMARK_(el.className)} else {
return and__23573__auto__19};

})())) {
const cls20 = el.className.trim().split(/\s+/).slice(0, 3).join(".");
if (squint_core.truth_(squint_core.not_empty(cls20))) {
squint_core.reset_BANG_(s18, `${squint_core.deref(s18)??''}${"."}${cls20??''}`)}} else {
}};
const r21 = el.getBoundingClientRect();
return `${squint_core.deref(s18)??''}${"  "}${Math.round(r21.width)??''}${"×"}${Math.round(r21.height)??''}`;

});
const styles6 = (function (el) {
const cs22 = getComputedStyle(el);
const keys23 = ["display", "position", "width", "height", "margin", "padding", "color", "backgroundColor", "fontSize", "fontFamily", "fontWeight", "border", "borderRadius", "overflow", "flexDirection", "justifyContent", "alignItems", "gridTemplateColumns", "gap"];
const out24 = ({});
for (let G__25 of squint_core.iterable(keys23)) {
const k26 = G__25;
const v27 = cs22[k26];
if (squint_core.truth_((() => {
const and__23573__auto__28 = v27;
if (squint_core.truth_(and__23573__auto__28)) {
return (!(v27 === "none") && (!(v27 === "normal") && (!(v27 === "0px") && (!(v27 === "auto") && (!(v27 === "visible") && (!(v27 === "static") && !(v27 === "rgba(0, 0, 0, 0)")))))))} else {
return and__23573__auto__28};

})())) {
(out24[k26] = v27)}
};
return out24;

});
const capture7 = (function (el, msg, mode) {
const r29 = el.getBoundingClientRect();
const html30 = (() => {
const h31 = el.outerHTML;
if ((h31.length > cfg1.maxHTML)) {
return `${h31.substring(0, cfg1.maxHTML)??''}${"\n<!-- truncated -->"}`} else {
return h31};

})();
return ({"selector": selector4(el), "mode": (() => {
const or__23542__auto__32 = mode;
if (squint_core.truth_(or__23542__auto__32)) {
return or__23542__auto__32} else {
return "edit"};

})(), "tagName": el.tagName.toLowerCase(), "ts": Date.now(), "computedStyles": styles6(el), "url": window.location.href, "boundingRect": ({"x": (r29.x + window.scrollX), "y": (r29.y + window.scrollY), "width": r29.width, "height": r29.height}), "outerHTML": html30, "message": (() => {
const or__23542__auto__33 = msg;
if (squint_core.truth_(or__23542__auto__33)) {
return or__23542__auto__33} else {
return ""};

})()});

});
const mk8 = (function (id, css) {
const el34 = doc3.createElement("div");
el34.id = id;
el34.style.cssText = css;
root4.appendChild(el34);
return el34;

});
const hl35 = mk8("__xi-design-hl", `${"position:fixed;pointer-events:none;z-index:2147483645;"}${"border:1.5px solid "}${C2.accent??''}${";background:"}${C2.accentBg??''}${";border-radius:4px;transition:all 60ms ease-out;display:none;"}`);
const tip36 = mk8("__xi-design-tip", `${"position:fixed;pointer-events:none;z-index:2147483646;"}${"background:"}${C2.text??''}${";color:"}${C2.surface??''}${";padding:4px 9px;border-radius:6px;font:11.5px/1.4 "}${mono6}${";"}${"box-shadow:0 4px 6px rgba(0,0,0,0.1),0 2px 4px rgba(0,0,0,0.06);display:none;max-width:420px;"}${"white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"}`);
const overlay37 = mk8("__xi-design-overlay", "position:fixed;top:0;left:0;width:100%;height:100%;z-index:2147483647;cursor:crosshair;display:none;");
const pill38 = mk8("__xi-design-pill", `${"display:flex;align-items:center;gap:8px;cursor:pointer;user-select:none;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";padding:7px 13px;border-radius:8px;font:13px/1.4 "}${sans5}${";"}${"box-shadow:0 4px 6px rgba(0,0,0,0.1),0 2px 4px rgba(0,0,0,0.06);"}`);
const dock39 = mk8("__xi-design-dock", "position:fixed;bottom:16px;right:16px;z-index:2147483646;display:flex;align-items:center;gap:10px;");
const agents_btn40 = mk8("__xi-design-agents-btn", `${"display:none;align-items:center;gap:6px;cursor:pointer;user-select:none;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";padding:6px 12px;border-radius:8px;font:13px/1.4 "}${sans5}${";"}${"box-shadow:0 4px 6px rgba(0,0,0,0.1),0 2px 4px rgba(0,0,0,0.06);"}`);
const pill_idle41 = (function () {
return pill38.innerHTML = `${spark10??''}${"<span style=\"font-weight:600;letter-spacing:0.01em;\">Design</span>"}${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">Ctrl+I/B to pick</span>"}`;

});
const pill_picking42 = (function () {
return pill38.innerHTML = `${spark_white11??''}${"<span style=\"font-weight:600;\">Pick an element</span>"}${"<span style=\"opacity:0.85;font-size:11.5px;\">Esc to stop</span>"}`;

});
const style_pill43 = (function (picking_QMARK_) {
if (squint_core.truth_(picking_QMARK_)) {
pill38.style.background = C2.accent;
pill38.style.color = "#fff";
pill38.style.borderColor = C2.accent;
return pill_picking42();
} else {
pill38.style.background = C2.surface;
pill38.style.color = C2.text;
pill38.style.borderColor = C2.border;
return pill_idle41();
};

});
const update_hl44 = (function (el) {
if (squint_core.not(el)) {
hl35.style.display = "none";
return tip36.style.display = "none";
} else {
const r67 = el.getBoundingClientRect();
hl35.style.display = "block";
hl35.style.top = `${r67.top??''}px`;
hl35.style.left = `${r67.left??''}px`;
hl35.style.width = `${r67.width??''}px`;
hl35.style.height = `${r67.height??''}px`;
tip36.style.display = "block";
tip36.textContent = info5(el);
tip36.style.top = `${(((r67.top > 33)) ? ((r67.top - 28)) : ((r67.bottom + 5)))??''}px`;
return tip36.style.left = `${Math.max(5, r67.left)??''}px`;
};

});
const close_popover45 = (function () {
const temp__23127__auto__68 = state13.popover;
if (squint_core.truth_(temp__23127__auto__68)) {
const p69 = temp__23127__auto__68;
p69.remove();
state13.popover = null};
return state13.selected = null;

});
const stop_picking46 = (function () {
close_popover45();
state13.picking = false;
state13.hovered = null;
overlay37.style.display = "none";
update_hl44(null);
return style_pill43(false);

});
const start_picking47 = (function () {
state13.picking = true;
overlay37.style.display = "block";
return style_pill43(true);

});
const toggle_picking48 = (function () {
if (squint_core.truth_(state13.picking)) {
return stop_picking46()} else {
return start_picking47()};

});
const show_toast49 = (function (text) {
const temp__23127__auto__70 = doc3.getElementById("__xi-design-toast");
if (squint_core.truth_(temp__23127__auto__70)) {
const old71 = temp__23127__auto__70;
old71.remove()};
const toast72 = mk8("__xi-design-toast", `${"position:fixed;bottom:64px;right:16px;z-index:2147483646;"}${"pointer-events:none;display:flex;align-items:center;gap:8px;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";padding:9px 16px;border-radius:10px;"}${"font:13px/1.4 "}${sans5}${";box-shadow:0 4px 6px rgba(0,0,0,0.1),0 2px 4px rgba(0,0,0,0.06);"}${"opacity:0;transform:translateY(4px);"}${"transition:opacity 180ms ease,transform 180ms ease;"}`);
toast72.innerHTML = `${spark10??''}${esc3(text)??''}`;
requestAnimationFrame((function () {
toast72.style.opacity = "1";
return toast72.style.transform = "translateY(0)";

}));
return setTimeout((function () {
toast72.style.opacity = "0";
return setTimeout((function () {
return toast72.remove();

}), 250);

}), 2600);

});
const submit50 = (function (msg, mode) {
const temp__23127__auto__73 = state13.selected;
if (squint_core.truth_(temp__23127__auto__73)) {
const el74 = temp__23127__auto__73;
window.__xiDesignQueue.push(capture7(el74, msg, (() => {
const or__23542__auto__75 = mode;
if (squint_core.truth_(or__23542__auto__75)) {
return or__23542__auto__75} else {
return "edit"};

})()));
stop_picking46();
return show_toast49((((mode === "choices")) ? ("Generating options — a sub-agent is on it") : ("Sent — a sub-agent is on it")));
};

});
const open_popover51 = (function () {
const el76 = state13.selected;
const pop77 = doc3.createElement("div");
const r78 = el76.getBoundingClientRect();
const vw79 = window.innerWidth;
const vh80 = window.innerHeight;
const w81 = 360;
pop77.id = "__xi-design-pop";
pop77.className = "dialkit-root";
pop77.style.cssText = `${"position:fixed;z-index:2147483647;width:"}${w81}${"px;"}${"font-family:"}${sans5}${";"}`;
pop77.innerHTML = `${"<div class=\"dial-panel\">"}${"<div class=\"dial-panel-head\" style=\"cursor:default;\">"}${"<span class=\"dial-panel-title\" style=\"display:flex;align-items:center;gap:7px;font-size:13px;\">"}${svg_icon7(sparkles_path8, 15, "var(--accent)")??''}${"<span>Describe the change</span></span></div>"}${"<div class=\"dial-panel-body\" style=\"gap:10px;\">"}${"<div style=\"font:11.5px/1.5 var(--font-mono);padding:7px 10px;"}${"background:var(--bg-1);border-radius:var(--radius-sm);color:var(--fg-1);"}${"word-break:break-all;max-height:56px;overflow:hidden;\">"}${esc3(info5(el76))??''}${"<br/><span style=\"color:var(--fg-2);\">"}${esc3(selector4(el76))??''}${"</span></div>"}${"<textarea id=\"__xi-design-msg\" placeholder=\"e.g. more padding, warmer background…\""}${" style=\"width:100%;height:64px;resize:none;background:var(--bg-1);"}${"color:var(--fg-0);border:var(--border-1);border-radius:var(--radius-sm);"}${"padding:9px 11px;font-size:13.5px;font-family:inherit;"}${"line-height:1.45;outline:none;box-sizing:border-box;\"></textarea>"}${"<div style=\"font-size:11px;color:var(--fg-2);\">"}${"Enter to send · Esc to re-pick</div>"}${"<div style=\"display:flex;align-items:center;gap:8px;\">"}${"<button id=\"__xi-design-choices\" class=\"dial-action\" "}${"style=\"width:auto;padding:7px 14px;font-size:13px;font-weight:600;\">Choices</button>"}${"<button id=\"__xi-design-send\" style=\"margin-left:auto;padding:7px 16px;"}${"border-radius:var(--radius-sm);border:none;background:var(--accent);color:#fff;"}${"cursor:pointer;font-size:13px;font-weight:600;font-family:inherit;\">Send</button>"}${"</div></div></div>"}`;
root4.appendChild(pop77);
state13.popover = pop77;
const ph82 = pop77.getBoundingClientRect().height;
const left83 = Math.min(Math.max(8, r78.left), (vw79 - w81 - 8));
const top84 = ((((r78.bottom + 8 + ph82) < vh80)) ? ((r78.bottom + 8)) : (Math.max(8, (r78.top - ph82 - 8))));
pop77.style.left = `${left83??''}px`;
pop77.style.top = `${top84??''}px`;
const msg_el85 = doc3.getElementById("__xi-design-msg");
setTimeout((function () {
return msg_el85.focus();

}), 50);
doc3.getElementById("__xi-design-send").addEventListener("click", (function () {
return submit50(msg_el85.value.trim(), "edit");

}));
doc3.getElementById("__xi-design-choices").addEventListener("click", (function () {
return submit50(msg_el85.value.trim(), "choices");

}));
return msg_el85.addEventListener("keydown", (function (e) {
e.stopPropagation();
if (squint_core.truth_(((e.key === "Enter") && squint_core.not(e.shiftKey)))) {
e.preventDefault();
return submit50(msg_el85.value.trim(), "edit");
} else {
if ((e.key === "Escape")) {
e.preventDefault();
close_popover45();
update_hl44(null);
return show_toast49("Re-pick — click another element");
} else {
return null}};

}));

});
const spinner_html52 = (function (size) {
return `${"<span style=\"display:inline-block;width:"}${size??''}${"px;height:"}${size??''}${"px;"}${"border:2px solid "}${C2.border??''}${";border-top-color:"}${C2.accent??''}${";border-radius:50%;animation:__xiDesignSpin 0.7s linear infinite;\"></span>"}`;

});
const working_QMARK_53 = (function (a) {
const or__23542__auto__86 = (a.status === "running");
if (or__23542__auto__86) {
return or__23542__auto__86} else {
return (a.commit === "committing")};

});
const list_sig54 = (function (arr) {
return arr.map((function (a) {
return `${a.id??''}${":"}${a.status??''}${":"}${(() => {
const or__23542__auto__87 = a.commit;
if (squint_core.truth_(or__23542__auto__87)) {
return or__23542__auto__87} else {
return ""};

})()??''}`;

})).join("|");

});
const render_agents_btn55 = (function () {
const arr88 = (() => {
const or__23542__auto__89 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__89)) {
return or__23542__auto__89} else {
return []};

})();
const n90 = arr88.length;
if ((n90 === 0)) {
agents_btn40.style.display = "none";
return state13.agentsSig = null;
} else {
const busy91 = arr88.some((function (a) {
return working_QMARK_53(a);

}));
const sig92 = `${n90??''}${":"}${busy91??''}`;
agents_btn40.style.display = "flex";
if (!(sig92 === state13.agentsSig)) {
state13.agentsSig = sig92;
return agents_btn40.innerHTML = `${((squint_core.truth_(busy91)) ? (spinner_html52(13)) : (spark10))??''}${"<span style=\"font-weight:600;\">"}${((squint_core.truth_(busy91)) ? ("Working") : ("Agents"))??''}${"</span>"}${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">"}${n90??''}${"</span>"}`;
};
};

});
const agent_row_html56 = (function (a) {
const status93 = a.status;
const kind94 = a.kind;
const commit95 = a.commit;
const chs96 = a.choices;
const n_ch97 = ((squint_core.truth_(chs96)) ? (chs96.length) : (0));
const right98 = (((status93 === "running")) ? (spinner_html52(12)) : ((((status93 === "error")) ? (`${"<span style=\"color:"}${C2.danger??''}${";font-size:11.5px;\">error</span>"}`) : ((((status93 === "stopped")) ? (`${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">stopped</span>"}`) : ((((kind94 === "choices")) ? ((((n_ch97 > 0)) ? (`${"<button data-choices-id=\""}${esc3(a.id)??''}${"\" "}${"style=\"padding:4px 11px;border-radius:6px;border:1px solid "}${C2.border??''}${";background:"}${C2.surface??''}${";color:"}${C2.text??''}${";cursor:pointer;"}${"font-size:12px;font-weight:600;font-family:inherit;\">View "}${n_ch97??''}${"</button>"}`) : (`${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">no options</span>"}`))) : ((((commit95 === "committed")) ? (check_ok12) : ((((commit95 === "committing")) ? (spinner_html52(12)) : ((((commit95 === "error")) ? (`${"<span style=\"color:"}${C2.danger??''}${";font-size:11.5px;\">commit failed</span>"}`) : ((((status93 === "done")) ? (`${"<button data-commit-id=\""}${esc3(a.id)??''}${"\" "}${"style=\"padding:4px 11px;border-radius:6px;border:none"}${";background:"}${C2.accent??''}${";color:#fff;cursor:pointer;"}${"font-size:12px;font-weight:600;font-family:inherit;\">Commit</button>"}`) : ((("else") ? ("") : (null))))))))))))))))));
const dot99 = (((status93 === "running")) ? (C2.accent) : ((((status93 === "error")) ? (C2.danger) : ((((status93 === "done")) ? (C2.success) : ((("else") ? (C2.textFaint) : (null))))))));
return `${"<div style=\"display:flex;align-items:center;gap:9px;padding:8px 2px;border-top:1px solid "}${C2.border??''}${";\">"}${"<span style=\"width:7px;height:7px;border-radius:50%;flex:none;background:"}${dot99??''}${";\"></span>"}${"<span style=\"flex:1;min-width:0;font-size:12.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;\">"}${esc3((() => {
const or__23542__auto__100 = a.label;
if (squint_core.truth_(or__23542__auto__100)) {
return or__23542__auto__100} else {
return "agent"};

})())??''}${"</span>"}${"<span style=\"flex:none;display:flex;align-items:center;min-height:22px;\">"}${right98??''}${"</span>"}${"</div>"}`;

});
const render_agents_list57 = (function () {
const temp__23127__auto__101 = state13.agentsPop;
if (squint_core.truth_(temp__23127__auto__101)) {
const pop102 = temp__23127__auto__101;
const arr103 = (() => {
const or__23542__auto__104 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__104)) {
return or__23542__auto__104} else {
return []};

})();
const sig105 = list_sig54(arr103);
const body106 = doc3.getElementById("__xi-design-agents-list");
if (squint_core.truth_((() => {
const and__23573__auto__107 = body106;
if (squint_core.truth_(and__23573__auto__107)) {
return !squint_core._EQ_(sig105, state13.agentsListSig)} else {
return and__23573__auto__107};

})())) {
state13.agentsListSig = sig105;
body106.innerHTML = (((0 === arr103.length)) ? (`${"<div style=\"color:"}${C2.textFaint??''}${";font-size:12.5px;padding:8px 2px;\">No design agents yet.</div>"}`) : (arr103.map((function (a) {
return agent_row_html56(a);

})).join("")));
for (let G__108 of squint_core.iterable(Array.from(body106.querySelectorAll("[data-commit-id]")))) {
const btn109 = G__108;
btn109.addEventListener("click", (function (e) {
e.stopPropagation();
return request_commit59(btn109.getAttribute("data-commit-id"));

}))
};
for (let G__110 of squint_core.iterable(Array.from(body106.querySelectorAll("[data-choices-id]")))) {
const btn111 = G__110;
btn111.addEventListener("click", (function (e) {
e.stopPropagation();
return open_choices_modal63(btn111.getAttribute("data-choices-id"));

}))
}
return null;
};
};

});
const render_agents58 = (function () {
render_agents_btn55();
return render_agents_list57();

});
const request_commit59 = (function (id) {
if (squint_core.truth_(window.__xiDesignCommitQueue)) {
} else {
window.__xiDesignCommitQueue = []};
window.__xiDesignCommitQueue.push(({"id": id, "ts": Date.now()}));
const arr112 = (() => {
const or__23542__auto__113 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__113)) {
return or__23542__auto__113} else {
return []};

})();
arr112.forEach((function (a) {
if (squint_core._EQ_(a.id, id)) {
return a.commit = "committing";
};

}));
state13.agentsSig = null;
state13.agentsListSig = null;
return render_agents58();

});
const request_pick60 = (function (id, index) {
if (squint_core.truth_(window.__xiDesignPickQueue)) {
} else {
window.__xiDesignPickQueue = []};
window.__xiDesignPickQueue.push(({"id": id, "index": index, "ts": Date.now()}));
close_choices_modal61();
return show_toast49("Applying — a sub-agent is on it");

});
const close_choices_modal61 = (function () {
const temp__23127__auto__114 = state13.choicesModal;
if (squint_core.truth_(temp__23127__auto__114)) {
const m115 = temp__23127__auto__114;
m115.remove();
state13.choicesModal = null;
return state13.choicesId = null;
};

});
const choices_card_html62 = (function (ch, i) {
return `${"<div style=\"border:1px solid "}${C2.border??''}${";border-radius:10px;overflow:hidden;"}${"display:flex;flex-direction:column;background:"}${C2.surface??''}${";\">"}${"<div style=\"height:200px;overflow:hidden;background:#fff;border-bottom:1px solid "}${C2.border??''}${";\">"}${"<iframe sandbox=\"\" style=\"width:100%;height:100%;border:none;pointer-events:none;\" "}${"srcdoc=\""}${esc3((() => {
const or__23542__auto__116 = ch.html;
if (squint_core.truth_(or__23542__auto__116)) {
return or__23542__auto__116} else {
return ""};

})())??''}${"\"></iframe></div>"}${"<div style=\"padding:11px 13px;display:flex;flex-direction:column;gap:6px;\">"}${"<div style=\"font-size:13.5px;font-weight:600;\">"}${esc3((() => {
const or__23542__auto__117 = ch.label;
if (squint_core.truth_(or__23542__auto__117)) {
return or__23542__auto__117} else {
return `${"Option "}${(i + 1)??''}`};

})())??''}${"</div>"}${(() => {
const note118 = ch.note;
if (squint_core.truth_((() => {
const and__23573__auto__119 = note118;
if (squint_core.truth_(and__23573__auto__119)) {
return !squint_core._EQ_(note118, "")} else {
return and__23573__auto__119};

})())) {
return `${"<div style=\"font-size:12px;color:"}${C2.textMuted??''}${";line-height:1.4;\">"}${esc3(note118)??''}${"</div>"}`} else {
return ""};

})()??''}${"<button data-pick-index=\""}${i??''}${"\" style=\"margin-top:4px;padding:7px 14px;"}${"border-radius:6px;border:none;background:"}${C2.accent??''}${";color:#fff;cursor:pointer;"}${"font-size:13px;font-weight:600;font-family:inherit;\">Pick this</button>"}${"</div></div>"}`;

});
const open_choices_modal63 = (function (id) {
close_choices_modal61();
const arr120 = (() => {
const or__23542__auto__121 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__121)) {
return or__23542__auto__121} else {
return []};

})();
const a122 = arr120.find((function (x) {
return squint_core._EQ_(x.id, id);

}));
const chs123 = (() => {
const and__23573__auto__124 = a122;
if (squint_core.truth_(and__23573__auto__124)) {
return a122.choices} else {
return and__23573__auto__124};

})();
if (squint_core.truth_((() => {
const and__23573__auto__125 = chs123;
if (squint_core.truth_(and__23573__auto__125)) {
return (chs123.length > 0)} else {
return and__23573__auto__125};

})())) {
const backdrop126 = doc3.createElement("div");
const panel127 = doc3.createElement("div");
backdrop126.id = "__xi-design-choices-modal";
backdrop126.style.cssText = `${"position:fixed;inset:0;z-index:2147483647;background:rgba(0,0,0,0.5);"}${"display:flex;align-items:center;justify-content:center;padding:24px;"}${"font-family:"}${sans5}${";"}`;
panel127.style.cssText = `${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";border-radius:12px;padding:18px 20px;width:min(880px,100%);max-height:86vh;"}${"overflow:auto;box-shadow:0 20px 40px rgba(0,0,0,0.25);"}`;
panel127.innerHTML = `${"<div style=\"display:flex;align-items:center;gap:8px;margin-bottom:14px;\">"}${spark10??''}${"<span style=\"font-size:16px;font-weight:600;\">Pick a direction</span>"}${"<button id=\"__xi-design-choices-close\" style=\"margin-left:auto;background:none;"}${"border:none;cursor:pointer;color:"}${C2.textMuted??''}${";font-size:20px;line-height:1;"}${"font-family:inherit;\">×</button></div>"}${"<div style=\"display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:14px;\">"}${chs123.map((function (ch, i) {
return choices_card_html62(ch, i);

})).join("")??''}${"</div>"}`;
backdrop126.appendChild(panel127);
root4.appendChild(backdrop126);
state13.choicesModal = backdrop126;
state13.choicesId = id;
backdrop126.addEventListener("click", (function (e) {
if (squint_core._EQ_(e.target, backdrop126)) {
return close_choices_modal61();
};

}));
doc3.getElementById("__xi-design-choices-close").addEventListener("click", (function (e) {
e.stopPropagation();
return close_choices_modal61();

}));
for (let G__128 of squint_core.iterable(Array.from(panel127.querySelectorAll("[data-pick-index]")))) {
const btn129 = G__128;
btn129.addEventListener("click", (function (e) {
e.stopPropagation();
return request_pick60(id, parseInt(btn129.getAttribute("data-pick-index"), 10));

}))
}
return null;
};

});
const close_agents_pop64 = (function () {
const temp__23127__auto__130 = state13.agentsPop;
if (squint_core.truth_(temp__23127__auto__130)) {
const p131 = temp__23127__auto__130;
p131.remove();
state13.agentsPop = null;
return state13.agentsListSig = null;
};

});
const open_agents_pop65 = (function () {
const pop132 = doc3.createElement("div");
pop132.id = "__xi-design-agents-pop";
pop132.style.cssText = `${"position:fixed;right:16px;bottom:62px;z-index:2147483647;width:322px;"}${"max-height:60vh;overflow:auto;background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";border-radius:10px;padding:12px 14px;font-family:"}${sans5}${";"}${"box-shadow:0 10px 15px rgba(0,0,0,0.1),0 4px 6px rgba(0,0,0,0.05);"}`;
pop132.innerHTML = `${"<div style=\"display:flex;align-items:baseline;gap:7px;margin-bottom:4px;\">"}${spark10??''}${"<span style=\"font-size:15px;font-weight:600;\">Design agents</span></div>"}${"<div id=\"__xi-design-agents-list\"></div>"}`;
root4.appendChild(pop132);
state13.agentsPop = pop132;
return render_agents_list57();

});
const toggle_agents_pop66 = (function () {
if (squint_core.truth_(state13.agentsPop)) {
return close_agents_pop64()} else {
return open_agents_pop65()};

});
style_pill43(false);
const sheet133 = doc3.createElement("style");
sheet133.id = "__xi-design-style";
sheet133.textContent = `${"@keyframes __xiDesignSpin{to{transform:rotate(360deg)}}\n"}${(() => {
const or__23542__auto__134 = cfg1.dialkitCss;
if (squint_core.truth_(or__23542__auto__134)) {
return or__23542__auto__134} else {
return ""};

})()??''}`;
root4.appendChild(sheet133);
dock39.appendChild(agents_btn40);
dock39.appendChild(pill38);
if (squint_core.truth_(window.__xiDesignCommitQueue)) {
} else {
window.__xiDesignCommitQueue = []};
window.__xiDesignRender = render_agents58;
render_agents58();
agents_btn40.addEventListener("click", (function (e) {
e.stopPropagation();
return toggle_agents_pop66();

}));
pill38.addEventListener("click", (function () {
return toggle_picking48();

}));
overlay37.addEventListener("mousemove", (function (e) {
if (squint_core.truth_(state13.selected)) {
return null} else {
overlay37.style.pointerEvents = "none";
const el135 = doc3.elementFromPoint(e.clientX, e.clientY);
overlay37.style.pointerEvents = "auto";
if (squint_core.truth_((() => {
const and__23573__auto__136 = el135;
if (squint_core.truth_(and__23573__auto__136)) {
const or__23542__auto__137 = squint_core.not(el135.id);
if (or__23542__auto__137) {
return or__23542__auto__137} else {
return !(0 === el135.id.indexOf("__xi-design"))};
} else {
return and__23573__auto__136};

})())) {
state13.hovered = el135;
return update_hl44(el135);
};
};

}));
overlay37.addEventListener("click", (function (e) {
e.preventDefault();
e.stopPropagation();
if (squint_core.truth_((() => {
const or__23542__auto__138 = state13.selected;
if (squint_core.truth_(or__23542__auto__138)) {
return or__23542__auto__138} else {
return squint_core.not(state13.hovered)};

})())) {
return null} else {
state13.selected = state13.hovered;
hl35.style.borderColor = C2.accent;
tip36.style.display = "none";
return open_popover51();
};

}));
const key_handler139 = (function key_handler (e) {
if (squint_core.not(window.__xiDesignActive)) {
return doc3.removeEventListener("keydown", key_handler, true)} else {
if (squint_core.truth_((() => {
const and__23573__auto__140 = e.ctrlKey;
if (squint_core.truth_(and__23573__auto__140)) {
return (squint_core.not(e.shiftKey) && (squint_core.not(e.altKey) && (squint_core.not(e.metaKey) && (() => {
const k141 = (() => {
const or__23542__auto__142 = e.key;
if (squint_core.truth_(or__23542__auto__142)) {
return or__23542__auto__142} else {
return ""};

})().toLowerCase();
const or__23542__auto__143 = (k141 === "i");
if (or__23542__auto__143) {
return or__23542__auto__143} else {
return (k141 === "b")};

})())))} else {
return and__23573__auto__140};

})())) {
e.preventDefault();
e.stopPropagation();
return toggle_picking48();
} else {
if (squint_core.truth_(((e.key === "Escape") && state13.picking))) {
e.preventDefault();
e.stopPropagation();
if (squint_core.truth_(state13.popover)) {
close_popover45();
return update_hl44(null);
} else {
return stop_picking46()};
} else {
return null}}};

});
doc3.addEventListener("keydown", key_handler139, true)};
