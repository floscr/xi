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
let panel_bg7 = `${"background:"}${C2.surface??''}${";border:1px solid "}${C2.border??''}${";"}`;
let btn_primary8 = `${"background:linear-gradient(180deg,"}${C2.accentBright??''}${","}${C2.accent??''}${");color:#fff;border:none;box-shadow:0 2px 12px "}${C2.accentGlow??''}${",inset 0 1px 0 oklch(1 0 0 / 0.25);"}`;
let svg_icon9 = (function (paths, size, color) {
return `${"<svg width=\""}${size??''}${"\" height=\""}${size??''}${"\" viewBox=\"0 0 24 24\" "}${"fill=\"none\" stroke=\""}${color??''}${"\" stroke-width=\"2\" "}${"stroke-linecap=\"round\" stroke-linejoin=\"round\" "}${"style=\"display:inline-block;vertical-align:middle;flex:none;\">"}${paths??''}${"</svg>"}`;

});
let sparkles_path10 = `${"<path d=\"M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962"}${"L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5"}${"A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 "}${"0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z\"/>"}${"<path d=\"M20 3v4\"/><path d=\"M22 5h-4\"/>"}${"<path d=\"M4 17v2\"/><path d=\"M5 18H3\"/>"}`;
let check_path11 = "<path d=\"M20 6 9 17l-5-5\"/>";
let spark12 = svg_icon9(sparkles_path10, 15, C2.accentBright);
let check_ok13 = svg_icon9(check_path11, 16, C2.success);
let chev_up14 = svg_icon9("<path d=\"m18 15-6-6-6 6\"/>", 13, "currentColor");
let chev_down15 = svg_icon9("<path d=\"m6 9 6 6 6-6\"/>", 13, "currentColor");
let state16 = ({"picking": false, "hovered": null, "selected": null, "selStack": null, "popover": null, "toastTimer": null});
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
const tip36 = mk8("__xi-design-tip", `${"position:fixed;pointer-events:none;z-index:2147483646;"}${panel_bg7}${"color:"}${C2.text??''}${";padding:4px 9px;border-radius:6px;font:11.5px/1.4 "}${mono6}${";"}${"display:none;max-width:420px;"}${"white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"}`);
const overlay37 = mk8("__xi-design-overlay", "position:fixed;top:0;left:0;width:100%;height:100%;z-index:2147483647;cursor:crosshair;display:none;");
const pill38 = mk8("__xi-design-pill", `${"display:flex;align-items:center;gap:8px;cursor:pointer;user-select:none;"}${"color:"}${C2.text??''}${";padding:9px 15px;font:12px/1.4 "}${sans5}${";"}`);
const dock39 = mk8("__xi-design-dock", `${"position:fixed;bottom:16px;right:16px;z-index:2147483646;"}${"display:flex;align-items:center;"}${panel_bg7}${"border-radius:999px;"}${"transition:border-color 150ms ease,box-shadow 150ms ease;"}`);
const agents_btn40 = mk8("__xi-design-agents-btn", `${"display:none;align-items:center;gap:7px;cursor:pointer;user-select:none;"}${"color:"}${C2.text??''}${";padding:9px 15px;font:12px/1.4 "}${sans5}${";"}${"border-right:1px solid "}${C2.border??''}${";"}`);
const pill_idle41 = (function () {
return pill38.innerHTML = `${spark12??''}${"<span style=\"font-weight:600;letter-spacing:0.01em;\">Design</span>"}${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">Ctrl+I/B to pick</span>"}`;

});
const pill_picking42 = (function () {
return pill38.innerHTML = `${spark12??''}${"<span style=\"font-weight:600;\">Pick an element</span>"}${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11px;\">Esc to stop</span>"}`;

});
const style_pill43 = (function (picking_QMARK_) {
if (squint_core.truth_(picking_QMARK_)) {
dock39.style.borderColor = C2.accentBorder;
dock39.style.boxShadow = `${"0 0 16px "}${C2.accentGlow??''}`;
return pill_picking42();
} else {
dock39.style.borderColor = C2.border;
dock39.style.boxShadow = "none";
return pill_idle41();
};

});
const update_hl44 = (function (el) {
if (squint_core.not(el)) {
hl35.style.display = "none";
return tip36.style.display = "none";
} else {
const r74 = el.getBoundingClientRect();
hl35.style.display = "block";
hl35.style.top = `${r74.top??''}px`;
hl35.style.left = `${r74.left??''}px`;
hl35.style.width = `${r74.width??''}px`;
hl35.style.height = `${r74.height??''}px`;
tip36.style.display = "block";
tip36.textContent = info5(el);
tip36.style.top = `${(((r74.top > 33)) ? ((r74.top - 28)) : ((r74.bottom + 5)))??''}px`;
return tip36.style.left = `${Math.max(5, r74.left)??''}px`;
};

});
const close_popover45 = (function () {
const temp__23127__auto__75 = state16.popover;
if (squint_core.truth_(temp__23127__auto__75)) {
const p76 = temp__23127__auto__75;
p76.remove();
state16.popover = null};
state16.selected = null;
return state16.selStack = null;

});
const stop_picking46 = (function () {
close_popover45();
state16.picking = false;
state16.hovered = null;
overlay37.style.display = "none";
update_hl44(null);
return style_pill43(false);

});
const start_picking47 = (function () {
state16.picking = true;
overlay37.style.display = "block";
return style_pill43(true);

});
const toggle_picking48 = (function () {
if (squint_core.truth_(state16.picking)) {
return stop_picking46()} else {
return start_picking47()};

});
const show_toast49 = (function (text) {
const temp__23127__auto__77 = doc3.getElementById("__xi-design-toast");
if (squint_core.truth_(temp__23127__auto__77)) {
const old78 = temp__23127__auto__77;
old78.remove()};
const toast79 = mk8("__xi-design-toast", `${"position:fixed;bottom:68px;right:16px;z-index:2147483646;"}${"pointer-events:none;display:flex;align-items:center;gap:8px;"}${panel_bg7}${"color:"}${C2.text??''}${";padding:10px 16px;border-radius:12px;"}${"font:12.5px/1.4 "}${sans5}${";"}${"opacity:0;transform:translateY(4px);"}${"transition:opacity 180ms ease,transform 180ms ease;"}`);
toast79.innerHTML = `${spark12??''}${esc3(text)??''}`;
requestAnimationFrame((function () {
toast79.style.opacity = "1";
return toast79.style.transform = "translateY(0)";

}));
return setTimeout((function () {
toast79.style.opacity = "0";
return setTimeout((function () {
return toast79.remove();

}), 250);

}), 2600);

});
const submit50 = (function (msg, mode) {
const temp__23127__auto__80 = state16.selected;
if (squint_core.truth_(temp__23127__auto__80)) {
const el81 = temp__23127__auto__80;
window.__xiDesignQueue.push(capture7(el81, msg, (() => {
const or__23542__auto__82 = mode;
if (squint_core.truth_(or__23542__auto__82)) {
return or__23542__auto__82} else {
return "edit"};

})()));
stop_picking46();
return show_toast49((((mode === "choices")) ? ("Generating options — a sub-agent is on it") : ("Sent — a sub-agent is on it")));
};

});
const target_info_html51 = (function (el) {
return `${esc3(info5(el))??''}${"<br/><span style=\"color:var(--fg-2);\">"}${esc3(selector4(el))??''}${"</span>"}`;

});
const can_grow_QMARK_52 = (function () {
const el83 = state16.selected;
const p84 = (() => {
const and__23573__auto__85 = el83;
if (squint_core.truth_(and__23573__auto__85)) {
return el83.parentElement} else {
return and__23573__auto__85};

})();
const and__23573__auto__86 = p84;
if (squint_core.truth_(and__23573__auto__86)) {
return !squint_core._EQ_(p84, root4)} else {
return and__23573__auto__86};

});
const refresh_target53 = (function () {
const temp__23127__auto__87 = state16.selected;
if (squint_core.truth_(temp__23127__auto__87)) {
const el88 = temp__23127__auto__87;
update_hl44(el88);
tip36.style.display = "none";
const temp__23127__auto__89 = doc3.getElementById("__xi-design-target");
if (squint_core.truth_(temp__23127__auto__89)) {
const t90 = temp__23127__auto__89;
t90.innerHTML = target_info_html51(el88)};
const temp__23127__auto__91 = doc3.getElementById("__xi-design-grow");
if (squint_core.truth_(temp__23127__auto__91)) {
const b92 = temp__23127__auto__91;
b92.style.opacity = ((squint_core.truth_(can_grow_QMARK_52())) ? ("1") : ("0.35"))};
const temp__23127__auto__93 = doc3.getElementById("__xi-design-shrink");
if (squint_core.truth_(temp__23127__auto__93)) {
const b94 = temp__23127__auto__93;
return b94.style.opacity = ((((() => {
const or__23542__auto__95 = state16.selStack;
if (squint_core.truth_(or__23542__auto__95)) {
return or__23542__auto__95} else {
return []};

})().length > 0)) ? ("1") : ("0.35"));
};
};

});
const grow_target54 = (function () {
if (squint_core.truth_(can_grow_QMARK_52())) {
const el96 = state16.selected;
if (squint_core.truth_(state16.selStack)) {
} else {
state16.selStack = []};
state16.selStack.push(el96);
state16.selected = el96.parentElement;
return refresh_target53();
};

});
const shrink_target55 = (function () {
const stk97 = state16.selStack;
if (squint_core.truth_((() => {
const and__23573__auto__98 = stk97;
if (squint_core.truth_(and__23573__auto__98)) {
return (stk97.length > 0)} else {
return and__23573__auto__98};

})())) {
state16.selected = stk97.pop();
return refresh_target53();
};

});
const open_popover56 = (function () {
const el99 = state16.selected;
const pop100 = doc3.createElement("div");
const r101 = el99.getBoundingClientRect();
const vw102 = window.innerWidth;
const vh103 = window.innerHeight;
const w104 = 360;
const tbtn105 = `${"flex:1;display:flex;align-items:center;justify-content:center;"}${"width:26px;padding:0;background:var(--bg-1);color:var(--fg-1);"}${"border:var(--border-1);border-radius:var(--radius-sm);cursor:pointer;"}`;
pop100.id = "__xi-design-pop";
pop100.className = "dialkit-root";
pop100.style.cssText = `${"position:fixed;z-index:2147483647;width:"}${w104}${"px;"}${"font-family:"}${sans5}${";"}`;
pop100.innerHTML = `${"<div class=\"dial-panel\">"}${"<div class=\"dial-panel-head\" style=\"cursor:default;\">"}${"<span class=\"dial-panel-title\" style=\"display:flex;align-items:center;gap:7px;font-size:13px;\">"}${svg_icon9(sparkles_path10, 15, "var(--accent)")??''}${"<span>Describe the change</span></span></div>"}${"<div class=\"dial-panel-body\" style=\"gap:10px;\">"}${"<div style=\"display:flex;align-items:stretch;gap:6px;\">"}${"<div id=\"__xi-design-target\" style=\"flex:1;min-width:0;"}${"font:11.5px/1.5 var(--font-mono);padding:7px 10px;"}${"background:var(--bg-1);border-radius:var(--radius-sm);color:var(--fg-1);"}${"word-break:break-all;max-height:56px;overflow:hidden;\">"}${target_info_html51(el99)??''}${"</div>"}${"<div style=\"display:flex;flex-direction:column;gap:4px;\">"}${"<button id=\"__xi-design-grow\" title=\"Widen target (parent element)\" style=\""}${tbtn105}${"\">"}${chev_up14??''}${"</button>"}${"<button id=\"__xi-design-shrink\" title=\"Narrow target (back)\" style=\""}${tbtn105}${"\">"}${chev_down15??''}${"</button>"}${"</div></div>"}${"<textarea id=\"__xi-design-msg\" placeholder=\"e.g. more padding, warmer background…\""}${" style=\"width:100%;height:64px;resize:none;background:var(--bg-1);"}${"color:var(--fg-0);border:var(--border-1);border-radius:var(--radius-sm);"}${"padding:9px 11px;font-size:13.5px;font-family:inherit;"}${"line-height:1.45;outline:none;box-sizing:border-box;\"></textarea>"}${"<div style=\"font-size:11px;color:var(--fg-2);\">"}${"Enter to send · Esc to re-pick</div>"}${"<div style=\"display:flex;align-items:center;gap:8px;\">"}${"<button id=\"__xi-design-choices\" class=\"dial-action\" "}${"style=\"width:auto;padding:7px 14px;font-size:13px;font-weight:600;\">Choices</button>"}${"<button id=\"__xi-design-send\" style=\"margin-left:auto;padding:7px 16px;"}${"border-radius:var(--radius-sm);"}${btn_primary8}${"cursor:pointer;font-size:13px;font-weight:600;font-family:inherit;\">Send</button>"}${"</div></div></div>"}`;
root4.appendChild(pop100);
state16.popover = pop100;
const ph106 = pop100.getBoundingClientRect().height;
const left107 = Math.min(Math.max(8, r101.left), (vw102 - w104 - 8));
const top108 = ((((r101.bottom + 8 + ph106) < vh103)) ? ((r101.bottom + 8)) : (Math.max(8, (r101.top - ph106 - 8))));
pop100.style.left = `${left107??''}px`;
pop100.style.top = `${top108??''}px`;
const msg_el109 = doc3.getElementById("__xi-design-msg");
setTimeout((function () {
return msg_el109.focus();

}), 50);
doc3.getElementById("__xi-design-send").addEventListener("click", (function () {
return submit50(msg_el109.value.trim(), "edit");

}));
doc3.getElementById("__xi-design-choices").addEventListener("click", (function () {
return submit50(msg_el109.value.trim(), "choices");

}));
doc3.getElementById("__xi-design-grow").addEventListener("click", (function () {
return grow_target54();

}));
doc3.getElementById("__xi-design-shrink").addEventListener("click", (function () {
return shrink_target55();

}));
refresh_target53();
return msg_el109.addEventListener("keydown", (function (e) {
e.stopPropagation();
if (squint_core.truth_(((e.key === "Enter") && squint_core.not(e.shiftKey)))) {
e.preventDefault();
return submit50(msg_el109.value.trim(), "edit");
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
const spinner_html57 = (function (size) {
return `${"<span style=\"display:inline-block;width:"}${size??''}${"px;height:"}${size??''}${"px;"}${"border:2px solid "}${C2.accentBg??''}${";border-top-color:"}${C2.accentBright??''}${";border-radius:50%;animation:__xiDesignSpin 1s linear infinite;\"></span>"}`;

});
const working_QMARK_58 = (function (a) {
const or__23542__auto__110 = (a.status === "running");
if (or__23542__auto__110) {
return or__23542__auto__110} else {
return (a.commit === "committing")};

});
const list_sig59 = (function (arr) {
return arr.map((function (a) {
return `${a.id??''}${":"}${a.status??''}${":"}${(() => {
const or__23542__auto__111 = a.commit;
if (squint_core.truth_(or__23542__auto__111)) {
return or__23542__auto__111} else {
return ""};

})()??''}`;

})).join("|");

});
const render_agents_btn60 = (function () {
const arr112 = (() => {
const or__23542__auto__113 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__113)) {
return or__23542__auto__113} else {
return []};

})();
const n114 = arr112.length;
if ((n114 === 0)) {
agents_btn40.style.display = "none";
return state16.agentsSig = null;
} else {
const busy115 = arr112.some((function (a) {
return working_QMARK_58(a);

}));
const sig116 = `${n114??''}${":"}${busy115??''}`;
agents_btn40.style.display = "flex";
if (!(sig116 === state16.agentsSig)) {
state16.agentsSig = sig116;
return agents_btn40.innerHTML = `${((squint_core.truth_(busy115)) ? (spinner_html57(13)) : (spark12))??''}${"<span style=\"font-weight:600;\">"}${((squint_core.truth_(busy115)) ? ("Working") : ("Agents"))??''}${"</span>"}${"<span style=\"background:"}${C2.accentBg??''}${";color:"}${C2.accentBright??''}${";border-radius:999px;padding:1px 7px;font-size:10.5px;font-weight:600;\">"}${n114??''}${"</span>"}`;
};
};

});
const agent_row_html61 = (function (a) {
const status117 = a.status;
const kind118 = a.kind;
const commit119 = a.commit;
const chs120 = a.choices;
const n_ch121 = ((squint_core.truth_(chs120)) ? (chs120.length) : (0));
const right122 = (((status117 === "running")) ? (spinner_html57(12)) : ((((status117 === "error")) ? (`${"<span style=\"color:"}${C2.danger??''}${";font-size:11.5px;\">error</span>"}`) : ((((status117 === "stopped")) ? (`${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">stopped</span>"}`) : ((((kind118 === "choices")) ? ((((n_ch121 > 0)) ? (`${"<button data-choices-id=\""}${esc3(a.id)??''}${"\" "}${"style=\"padding:4px 11px;border-radius:8px;border:1px solid oklch(1 0 0 / 0.12)"}${";background:transparent;color:"}${C2.textMuted??''}${";cursor:pointer;"}${"font-size:12px;font-weight:600;font-family:inherit;\">View "}${n_ch121??''}${"</button>"}`) : (`${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">no options</span>"}`))) : ((((commit119 === "committed")) ? (check_ok13) : ((((commit119 === "committing")) ? (spinner_html57(12)) : ((((commit119 === "error")) ? (`${"<span style=\"color:"}${C2.danger??''}${";font-size:11.5px;\">commit failed</span>"}`) : ((((status117 === "done")) ? (`${"<button data-commit-id=\""}${esc3(a.id)??''}${"\" "}${"style=\"padding:5px 13px;border-radius:8px;"}${btn_primary8}${"cursor:pointer;"}${"font-size:12px;font-weight:600;font-family:inherit;\">Commit</button>"}`) : ((("else") ? ("") : (null))))))))))))))))));
const dot123 = (((status117 === "running")) ? (C2.success) : ((((status117 === "error")) ? (C2.danger) : ((((status117 === "done")) ? (C2.success) : ((("else") ? (C2.textFaint) : (null))))))));
const dot_glow124 = (((status117 === "running")) ? (`${"box-shadow:0 0 8px "}${dot123??''}${";"}`) : (""));
return `${"<div style=\"display:flex;align-items:center;gap:9px;padding:8px 2px;border-top:1px solid oklch(1 0 0 / 0.05);\">"}${"<span style=\"width:7px;height:7px;border-radius:50%;flex:none;background:"}${dot123??''}${";"}${dot_glow124??''}${"\"></span>"}${"<span style=\"flex:1;min-width:0;font-size:12.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;\">"}${esc3((() => {
const or__23542__auto__125 = a.label;
if (squint_core.truth_(or__23542__auto__125)) {
return or__23542__auto__125} else {
return "agent"};

})())??''}${"</span>"}${"<span style=\"flex:none;display:flex;align-items:center;min-height:22px;\">"}${right122??''}${"</span>"}${"</div>"}`;

});
const render_agents_list62 = (function () {
const temp__23127__auto__126 = state16.agentsPop;
if (squint_core.truth_(temp__23127__auto__126)) {
const pop127 = temp__23127__auto__126;
const arr128 = (() => {
const or__23542__auto__129 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__129)) {
return or__23542__auto__129} else {
return []};

})();
const sig130 = list_sig59(arr128);
const body131 = doc3.getElementById("__xi-design-agents-list");
if (squint_core.truth_((() => {
const and__23573__auto__132 = body131;
if (squint_core.truth_(and__23573__auto__132)) {
return !squint_core._EQ_(sig130, state16.agentsListSig)} else {
return and__23573__auto__132};

})())) {
state16.agentsListSig = sig130;
body131.innerHTML = (((0 === arr128.length)) ? (`${"<div style=\"color:"}${C2.textFaint??''}${";font-size:12.5px;padding:8px 2px;\">No design agents yet.</div>"}`) : (arr128.map((function (a) {
return agent_row_html61(a);

})).join("")));
for (let G__133 of squint_core.iterable(Array.from(body131.querySelectorAll("[data-commit-id]")))) {
const btn134 = G__133;
btn134.addEventListener("click", (function (e) {
e.stopPropagation();
return request_commit64(btn134.getAttribute("data-commit-id"));

}))
};
for (let G__135 of squint_core.iterable(Array.from(body131.querySelectorAll("[data-choices-id]")))) {
const btn136 = G__135;
btn136.addEventListener("click", (function (e) {
e.stopPropagation();
return open_choices_modal70(btn136.getAttribute("data-choices-id"));

}))
}
return null;
};
};

});
const render_agents63 = (function () {
render_agents_btn60();
return render_agents_list62();

});
const request_commit64 = (function (id) {
if (squint_core.truth_(window.__xiDesignCommitQueue)) {
} else {
window.__xiDesignCommitQueue = []};
window.__xiDesignCommitQueue.push(({"id": id, "ts": Date.now()}));
const arr137 = (() => {
const or__23542__auto__138 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__138)) {
return or__23542__auto__138} else {
return []};

})();
arr137.forEach((function (a) {
if (squint_core._EQ_(a.id, id)) {
return a.commit = "committing";
};

}));
state16.agentsSig = null;
state16.agentsListSig = null;
return render_agents63();

});
const request_pick65 = (function (id, index) {
if (squint_core.truth_(window.__xiDesignPickQueue)) {
} else {
window.__xiDesignPickQueue = []};
window.__xiDesignPickQueue.push(({"id": id, "index": index, "ts": Date.now()}));
close_choices_modal66();
return show_toast49("Applying — a sub-agent is on it");

});
const close_choices_modal66 = (function () {
close_choice_full67();
const temp__23127__auto__139 = state16.choicesModal;
if (squint_core.truth_(temp__23127__auto__139)) {
const m140 = temp__23127__auto__139;
m140.remove();
state16.choicesModal = null;
return state16.choicesId = null;
};

});
const close_choice_full67 = (function () {
const temp__23127__auto__141 = state16.choiceFull;
if (squint_core.truth_(temp__23127__auto__141)) {
const f142 = temp__23127__auto__141;
f142.remove();
return state16.choiceFull = null;
};

});
const open_choice_full68 = (function (id, ch, i) {
close_choice_full67();
const ov143 = doc3.createElement("div");
const label144 = (() => {
const or__23542__auto__145 = ch.label;
if (squint_core.truth_(or__23542__auto__145)) {
return or__23542__auto__145} else {
return `${"Option "}${(i + 1)??''}`};

})();
const note146 = ch.note;
ov143.id = "__xi-design-choice-full";
ov143.style.cssText = `${"position:fixed;inset:0;z-index:2147483647;background:oklch(0 0 0 / 0.72);"}${"-webkit-backdrop-filter:blur(4px);backdrop-filter:blur(4px);"}${"display:flex;flex-direction:column;padding:18px;gap:12px;"}${"font-family:"}${sans5}${";"}`;
ov143.innerHTML = `${"<div style=\"display:flex;align-items:center;gap:10px;color:"}${C2.text??''}${";min-height:34px;\">"}${"<button id=\"__xi-design-full-back\" style=\"background:none;border:1px solid "}${C2.border??''}${";border-radius:8px;color:"}${C2.textMuted??''}${";cursor:pointer;padding:6px 12px;font-size:12.5px;font-weight:600;"}${"font-family:inherit;\">← Back</button>"}${"<span style=\"font-size:15px;font-weight:600;flex:none;\">"}${esc3(label144)??''}${"</span>"}${((squint_core.truth_(squint_core.not_empty(note146))) ? (`${"<span style=\"font-size:12.5px;color:"}${C2.textMuted??''}${";overflow:hidden;text-overflow:ellipsis;white-space:nowrap;"}${"min-width:0;flex:1;\">"}${esc3(note146)??''}${"</span>"}`) : ("<span style=\"flex:1;\"></span>"))??''}${"<button id=\"__xi-design-full-pick\" style=\"flex:none;padding:7px 18px;"}${"border-radius:8px;"}${btn_primary8}${"cursor:pointer;font-size:13px;"}${"font-weight:600;font-family:inherit;\">Pick this</button>"}${"<button id=\"__xi-design-full-close\" style=\"flex:none;background:none;border:none;"}${"cursor:pointer;color:"}${C2.textMuted??''}${";font-size:22px;line-height:1;"}${"font-family:inherit;padding:2px 6px;\">×</button></div>"}${"<iframe sandbox=\"\" style=\"flex:1;width:100%;border:none;border-radius:12px;"}${"background:#fff;\" srcdoc=\""}${esc3((() => {
const or__23542__auto__147 = ch.html;
if (squint_core.truth_(or__23542__auto__147)) {
return or__23542__auto__147} else {
return ""};

})())??''}${"\"></iframe>"}`;
root4.appendChild(ov143);
state16.choiceFull = ov143;
doc3.getElementById("__xi-design-full-back").addEventListener("click", (function (e) {
e.stopPropagation();
return close_choice_full67();

}));
doc3.getElementById("__xi-design-full-close").addEventListener("click", (function (e) {
e.stopPropagation();
return close_choice_full67();

}));
return doc3.getElementById("__xi-design-full-pick").addEventListener("click", (function (e) {
e.stopPropagation();
return request_pick65(id, i);

}));

});
const choices_card_html69 = (function (ch, i) {
return `${"<div style=\"border:1px solid "}${C2.border??''}${";border-radius:12px;overflow:hidden;"}${"display:flex;flex-direction:column;background:"}${C2.surfaceMuted??''}${";\">"}${"<div data-expand-index=\""}${i??''}${"\" title=\"Click to expand\" "}${"style=\"height:200px;overflow:auto;overscroll-behavior:contain;"}${"background:#fff;border-bottom:1px solid "}${C2.border??''}${";cursor:zoom-in;\">"}${"<iframe sandbox=\"\" style=\"width:100%;height:1200px;border:none;pointer-events:none;display:block;\" "}${"srcdoc=\""}${esc3((() => {
const or__23542__auto__148 = ch.html;
if (squint_core.truth_(or__23542__auto__148)) {
return or__23542__auto__148} else {
return ""};

})())??''}${"\"></iframe></div>"}${"<div style=\"padding:11px 13px;display:flex;flex-direction:column;gap:6px;\">"}${"<div style=\"font-size:13.5px;font-weight:600;\">"}${esc3((() => {
const or__23542__auto__149 = ch.label;
if (squint_core.truth_(or__23542__auto__149)) {
return or__23542__auto__149} else {
return `${"Option "}${(i + 1)??''}`};

})())??''}${"</div>"}${(() => {
const note150 = ch.note;
if (squint_core.truth_((() => {
const and__23573__auto__151 = note150;
if (squint_core.truth_(and__23573__auto__151)) {
return !squint_core._EQ_(note150, "")} else {
return and__23573__auto__151};

})())) {
return `${"<div style=\"font-size:12px;color:"}${C2.textMuted??''}${";line-height:1.4;\">"}${esc3(note150)??''}${"</div>"}`} else {
return ""};

})()??''}${"<button data-pick-index=\""}${i??''}${"\" style=\"margin-top:4px;padding:7px 14px;"}${"border-radius:8px;"}${btn_primary8}${"cursor:pointer;"}${"font-size:13px;font-weight:600;font-family:inherit;\">Pick this</button>"}${"</div></div>"}`;

});
const open_choices_modal70 = (function (id) {
close_choices_modal66();
const arr152 = (() => {
const or__23542__auto__153 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__153)) {
return or__23542__auto__153} else {
return []};

})();
const a154 = arr152.find((function (x) {
return squint_core._EQ_(x.id, id);

}));
const chs155 = (() => {
const and__23573__auto__156 = a154;
if (squint_core.truth_(and__23573__auto__156)) {
return a154.choices} else {
return and__23573__auto__156};

})();
if (squint_core.truth_((() => {
const and__23573__auto__157 = chs155;
if (squint_core.truth_(and__23573__auto__157)) {
return (chs155.length > 0)} else {
return and__23573__auto__157};

})())) {
const backdrop158 = doc3.createElement("div");
const panel159 = doc3.createElement("div");
backdrop158.id = "__xi-design-choices-modal";
backdrop158.style.cssText = `${"position:fixed;inset:0;z-index:2147483647;background:oklch(0 0 0 / 0.6);"}${"-webkit-backdrop-filter:blur(4px);backdrop-filter:blur(4px);"}${"display:flex;align-items:center;justify-content:center;padding:24px;"}${"font-family:"}${sans5}${";"}`;
panel159.style.cssText = `${panel_bg7}${"color:"}${C2.text??''}${";border-radius:14px;padding:18px 20px;width:min(880px,100%);max-height:86vh;"}${"overflow:auto;overscroll-behavior:contain;"}`;
panel159.innerHTML = `${"<div style=\"display:flex;align-items:center;gap:8px;margin-bottom:14px;\">"}${spark12??''}${"<span style=\"font-size:16px;font-weight:600;\">Pick a direction</span>"}${"<button id=\"__xi-design-choices-close\" style=\"margin-left:auto;background:none;"}${"border:none;cursor:pointer;color:"}${C2.textMuted??''}${";font-size:20px;line-height:1;"}${"font-family:inherit;\">×</button></div>"}${"<div style=\"display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:14px;\">"}${chs155.map((function (ch, i) {
return choices_card_html69(ch, i);

})).join("")??''}${"</div>"}`;
backdrop158.appendChild(panel159);
root4.appendChild(backdrop158);
state16.choicesModal = backdrop158;
state16.choicesId = id;
backdrop158.addEventListener("click", (function (e) {
if (squint_core._EQ_(e.target, backdrop158)) {
return close_choices_modal66();
};

}));
backdrop158.addEventListener("wheel", (function (e) {
if (squint_core._EQ_(e.target, backdrop158)) {
return e.preventDefault();
};

}), ({"passive": false}));
doc3.getElementById("__xi-design-choices-close").addEventListener("click", (function (e) {
e.stopPropagation();
return close_choices_modal66();

}));
for (let G__160 of squint_core.iterable(Array.from(panel159.querySelectorAll("[data-pick-index]")))) {
const btn161 = G__160;
btn161.addEventListener("click", (function (e) {
e.stopPropagation();
return request_pick65(id, parseInt(btn161.getAttribute("data-pick-index"), 10));

}))
};
for (let G__162 of squint_core.iterable(Array.from(panel159.querySelectorAll("[data-expand-index]")))) {
const pv163 = G__162;
pv163.addEventListener("click", (function (e) {
e.stopPropagation();
const i164 = parseInt(pv163.getAttribute("data-expand-index"), 10);
return open_choice_full68(id, chs155[i164], i164);

}))
}
return null;
};

});
const close_agents_pop71 = (function () {
const temp__23127__auto__165 = state16.agentsPop;
if (squint_core.truth_(temp__23127__auto__165)) {
const p166 = temp__23127__auto__165;
p166.remove();
state16.agentsPop = null;
return state16.agentsListSig = null;
};

});
const open_agents_pop72 = (function () {
const pop167 = doc3.createElement("div");
pop167.id = "__xi-design-agents-pop";
pop167.style.cssText = `${"position:fixed;right:16px;bottom:68px;z-index:2147483647;width:340px;"}${"max-height:60vh;overflow:auto;"}${panel_bg7}${"color:"}${C2.text??''}${";border-radius:14px;padding:12px 14px;font-family:"}${sans5}${";"}`;
pop167.innerHTML = `${"<div style=\"display:flex;align-items:baseline;gap:7px;margin-bottom:4px;\">"}${spark12??''}${"<span style=\"font-size:15px;font-weight:600;\">Design agents</span></div>"}${"<div id=\"__xi-design-agents-list\"></div>"}`;
root4.appendChild(pop167);
state16.agentsPop = pop167;
return render_agents_list62();

});
const toggle_agents_pop73 = (function () {
if (squint_core.truth_(state16.agentsPop)) {
return close_agents_pop71()} else {
return open_agents_pop72()};

});
style_pill43(false);
const sheet168 = doc3.createElement("style");
sheet168.id = "__xi-design-style";
sheet168.textContent = `${"@keyframes __xiDesignSpin{to{transform:rotate(360deg)}}\n"}${(() => {
const or__23542__auto__169 = cfg1.dialkitCss;
if (squint_core.truth_(or__23542__auto__169)) {
return or__23542__auto__169} else {
return ""};

})()??''}`;
root4.appendChild(sheet168);
dock39.appendChild(agents_btn40);
dock39.appendChild(pill38);
if (squint_core.truth_(window.__xiDesignCommitQueue)) {
} else {
window.__xiDesignCommitQueue = []};
window.__xiDesignRender = render_agents63;
render_agents63();
agents_btn40.addEventListener("click", (function (e) {
e.stopPropagation();
return toggle_agents_pop73();

}));
pill38.addEventListener("click", (function () {
return toggle_picking48();

}));
overlay37.addEventListener("mousemove", (function (e) {
if (squint_core.truth_(state16.selected)) {
return null} else {
overlay37.style.pointerEvents = "none";
const el170 = doc3.elementFromPoint(e.clientX, e.clientY);
overlay37.style.pointerEvents = "auto";
if (squint_core.truth_((() => {
const and__23573__auto__171 = el170;
if (squint_core.truth_(and__23573__auto__171)) {
const or__23542__auto__172 = squint_core.not(el170.id);
if (or__23542__auto__172) {
return or__23542__auto__172} else {
return !(0 === el170.id.indexOf("__xi-design"))};
} else {
return and__23573__auto__171};

})())) {
state16.hovered = el170;
return update_hl44(el170);
};
};

}));
overlay37.addEventListener("click", (function (e) {
e.preventDefault();
e.stopPropagation();
if (squint_core.truth_((() => {
const or__23542__auto__173 = state16.selected;
if (squint_core.truth_(or__23542__auto__173)) {
return or__23542__auto__173} else {
return squint_core.not(state16.hovered)};

})())) {
return null} else {
state16.selected = state16.hovered;
state16.selStack = [];
hl35.style.borderColor = C2.accent;
tip36.style.display = "none";
return open_popover56();
};

}));
const key_handler174 = (function key_handler (e) {
if (squint_core.not(window.__xiDesignActive)) {
return doc3.removeEventListener("keydown", key_handler, true)} else {
if (squint_core.truth_((() => {
const and__23573__auto__175 = e.ctrlKey;
if (squint_core.truth_(and__23573__auto__175)) {
return (squint_core.not(e.shiftKey) && (squint_core.not(e.altKey) && (squint_core.not(e.metaKey) && (() => {
const k176 = (() => {
const or__23542__auto__177 = e.key;
if (squint_core.truth_(or__23542__auto__177)) {
return or__23542__auto__177} else {
return ""};

})().toLowerCase();
const or__23542__auto__178 = (k176 === "i");
if (or__23542__auto__178) {
return or__23542__auto__178} else {
return (k176 === "b")};

})())))} else {
return and__23573__auto__175};

})())) {
e.preventDefault();
e.stopPropagation();
return toggle_picking48();
} else {
if (squint_core.truth_(((e.key === "Escape") && state16.choiceFull))) {
e.preventDefault();
e.stopPropagation();
return close_choice_full67();
} else {
if (squint_core.truth_(((e.key === "Escape") && state16.choicesModal))) {
e.preventDefault();
e.stopPropagation();
return close_choices_modal66();
} else {
if (squint_core.truth_(((e.key === "Escape") && state16.picking))) {
e.preventDefault();
e.stopPropagation();
if (squint_core.truth_(state16.popover)) {
close_popover45();
return update_hl44(null);
} else {
return stop_picking46()};
} else {
return null}}}}};

});
doc3.addEventListener("keydown", key_handler174, true)};
