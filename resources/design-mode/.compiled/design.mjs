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
let serif5 = "Copernicus, Charter, Georgia, 'Times New Roman', serif";
let sans6 = "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
let mono7 = "ui-monospace, 'SF Mono', Menlo, monospace";
let state8 = ({"picking": false, "hovered": null, "selected": null, "popover": null, "toastTimer": null});
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
const capture7 = (function (el, msg) {
const r29 = el.getBoundingClientRect();
const html30 = (() => {
const h31 = el.outerHTML;
if ((h31.length > cfg1.maxHTML)) {
return `${h31.substring(0, cfg1.maxHTML)??''}${"\n<!-- truncated -->"}`} else {
return h31};

})();
return ({"selector": selector4(el), "tagName": el.tagName.toLowerCase(), "message": (() => {
const or__23542__auto__32 = msg;
if (squint_core.truth_(or__23542__auto__32)) {
return or__23542__auto__32} else {
return ""};

})(), "outerHTML": html30, "computedStyles": styles6(el), "url": window.location.href, "ts": Date.now(), "boundingRect": ({"x": (r29.x + window.scrollX), "y": (r29.y + window.scrollY), "width": r29.width, "height": r29.height})});

});
const mk8 = (function (id, css) {
const el33 = doc3.createElement("div");
el33.id = id;
el33.style.cssText = css;
root4.appendChild(el33);
return el33;

});
const hl34 = mk8("__xi-design-hl", `${"position:fixed;pointer-events:none;z-index:2147483645;"}${"border:1.5px solid "}${C2.accent??''}${";background:"}${C2.accentBg??''}${";border-radius:4px;transition:all 60ms ease-out;display:none;"}`);
const tip35 = mk8("__xi-design-tip", `${"position:fixed;pointer-events:none;z-index:2147483646;"}${"background:"}${C2.text??''}${";color:"}${C2.surface??''}${";padding:4px 9px;border-radius:6px;font:11.5px/1.4 "}${mono7}${";"}${"box-shadow:0 2px 10px rgba(30,30,28,0.25);display:none;max-width:420px;"}${"white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"}`);
const overlay36 = mk8("__xi-design-overlay", "position:fixed;top:0;left:0;width:100%;height:100%;z-index:2147483647;cursor:crosshair;display:none;");
const pill37 = mk8("__xi-design-pill", `${"display:flex;align-items:center;gap:8px;cursor:pointer;user-select:none;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";padding:7px 14px;border-radius:999px;font:13px/1.4 "}${sans6}${";"}${"box-shadow:0 4px 24px rgba(30,30,28,0.14),0 1px 3px rgba(30,30,28,0.08);"}`);
const dock38 = mk8("__xi-design-dock", "position:fixed;bottom:16px;right:16px;z-index:2147483646;display:flex;align-items:center;gap:10px;");
const agents_btn39 = mk8("__xi-design-agents-btn", `${"display:none;align-items:center;gap:6px;cursor:pointer;user-select:none;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";padding:6px 12px;border-radius:999px;font:13px/1.4 "}${sans6}${";"}${"box-shadow:0 4px 24px rgba(30,30,28,0.14),0 1px 3px rgba(30,30,28,0.08);"}`);
const pill_idle40 = (function () {
return pill37.innerHTML = `${"<span style=\"color:"}${C2.accent??''}${";font-size:14px;\">✦</span>"}${"<span style=\"font-family:"}${serif5}${";font-weight:600;letter-spacing:0.01em;\">Design</span>"}${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">Ctrl+I/B to pick</span>"}`;

});
const pill_picking41 = (function () {
return pill37.innerHTML = `${"<span style=\"font-size:14px;\">✦</span>"}${"<span style=\"font-family:"}${serif5}${";font-weight:600;\">Pick an element</span>"}${"<span style=\"opacity:0.75;font-size:11.5px;\">Esc to stop</span>"}`;

});
const style_pill42 = (function (picking_QMARK_) {
if (squint_core.truth_(picking_QMARK_)) {
pill37.style.background = C2.accent;
pill37.style.color = "#fff";
pill37.style.borderColor = C2.accent;
return pill_picking41();
} else {
pill37.style.background = C2.surface;
pill37.style.color = C2.text;
pill37.style.borderColor = C2.border;
return pill_idle40();
};

});
const update_hl43 = (function (el) {
if (squint_core.not(el)) {
hl34.style.display = "none";
return tip35.style.display = "none";
} else {
const r62 = el.getBoundingClientRect();
hl34.style.display = "block";
hl34.style.top = `${r62.top??''}px`;
hl34.style.left = `${r62.left??''}px`;
hl34.style.width = `${r62.width??''}px`;
hl34.style.height = `${r62.height??''}px`;
tip35.style.display = "block";
tip35.textContent = info5(el);
tip35.style.top = `${(((r62.top > 33)) ? ((r62.top - 28)) : ((r62.bottom + 5)))??''}px`;
return tip35.style.left = `${Math.max(5, r62.left)??''}px`;
};

});
const close_popover44 = (function () {
const temp__23127__auto__63 = state8.popover;
if (squint_core.truth_(temp__23127__auto__63)) {
const p64 = temp__23127__auto__63;
p64.remove();
state8.popover = null};
return state8.selected = null;

});
const stop_picking45 = (function () {
close_popover44();
state8.picking = false;
state8.hovered = null;
overlay36.style.display = "none";
update_hl43(null);
return style_pill42(false);

});
const start_picking46 = (function () {
state8.picking = true;
overlay36.style.display = "block";
return style_pill42(true);

});
const toggle_picking47 = (function () {
if (squint_core.truth_(state8.picking)) {
return stop_picking45()} else {
return start_picking46()};

});
const show_toast48 = (function (text) {
const temp__23127__auto__65 = doc3.getElementById("__xi-design-toast");
if (squint_core.truth_(temp__23127__auto__65)) {
const old66 = temp__23127__auto__65;
old66.remove()};
const toast67 = mk8("__xi-design-toast", `${"position:fixed;bottom:64px;right:16px;z-index:2147483646;"}${"pointer-events:none;display:flex;align-items:center;gap:8px;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";padding:9px 16px;border-radius:12px;"}${"font:13px/1.4 "}${sans6}${";box-shadow:0 4px 24px rgba(30,30,28,0.14);"}${"opacity:0;transform:translateY(4px);"}${"transition:opacity 180ms ease,transform 180ms ease;"}`);
toast67.innerHTML = `${"<span style=\"color:"}${C2.accent??''}${";\">✦</span>"}${esc3(text)??''}`;
requestAnimationFrame((function () {
toast67.style.opacity = "1";
return toast67.style.transform = "translateY(0)";

}));
return setTimeout((function () {
toast67.style.opacity = "0";
return setTimeout((function () {
return toast67.remove();

}), 250);

}), 2600);

});
const submit49 = (function (msg) {
const temp__23127__auto__68 = state8.selected;
if (squint_core.truth_(temp__23127__auto__68)) {
const el69 = temp__23127__auto__68;
window.__xiDesignQueue.push(capture7(el69, msg));
stop_picking45();
return show_toast48("Sent — a sub-agent is on it");
};

});
const open_popover50 = (function () {
const el70 = state8.selected;
const pop71 = doc3.createElement("div");
const r72 = el70.getBoundingClientRect();
const vw73 = window.innerWidth;
const vh74 = window.innerHeight;
const w75 = 360;
pop71.id = "__xi-design-pop";
pop71.style.cssText = `${"position:fixed;z-index:2147483647;width:"}${w75}${"px;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";border-radius:14px;padding:14px;"}${"box-shadow:0 8px 40px rgba(30,30,28,0.18),0 2px 8px rgba(30,30,28,0.08);"}${"font-family:"}${sans6}${";"}`;
pop71.innerHTML = `${"<div style=\"display:flex;align-items:baseline;gap:7px;margin-bottom:10px;\">"}${"<span style=\"color:"}${C2.accent??''}${";font-size:14px;\">✦</span>"}${"<span style=\"font-family:"}${serif5}${";font-size:15px;font-weight:600;\">Describe the change</span>"}${"</div>"}${"<div style=\"font:11.5px/1.5 "}${mono7}${";padding:7px 10px;margin-bottom:10px;"}${"background:"}${C2.surfaceMuted??''}${";border-radius:8px;color:"}${C2.textMuted??''}${";word-break:break-all;max-height:56px;overflow:hidden;\">"}${esc3(info5(el70))??''}${"<br/><span style=\"color:"}${C2.textFaint??''}${";\">"}${esc3(selector4(el70))??''}${"</span></div>"}${"<textarea id=\"__xi-design-msg\" placeholder=\"e.g. more padding, warmer background…\""}${" style=\"width:100%;height:64px;resize:none;background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";border-radius:10px;padding:9px 11px;font-size:13.5px;font-family:inherit;"}${"line-height:1.45;outline:none;box-sizing:border-box;\"></textarea>"}${"<div style=\"display:flex;align-items:center;gap:8px;margin-top:10px;\">"}${"<span style=\"font-size:11px;color:"}${C2.textFaint??''}${";margin-right:auto;\">"}${"Enter to send · Esc to re-pick</span>"}${"<button id=\"__xi-design-send\" style=\"padding:7px 16px;border-radius:9px;border:none;"}${"background:"}${C2.accent??''}${";color:#fff;cursor:pointer;font-size:13px;"}${"font-weight:600;font-family:inherit;\">Send</button>"}${"</div>"}`;
root4.appendChild(pop71);
state8.popover = pop71;
const ph76 = pop71.getBoundingClientRect().height;
const left77 = Math.min(Math.max(8, r72.left), (vw73 - w75 - 8));
const top78 = ((((r72.bottom + 8 + ph76) < vh74)) ? ((r72.bottom + 8)) : (Math.max(8, (r72.top - ph76 - 8))));
pop71.style.left = `${left77??''}px`;
pop71.style.top = `${top78??''}px`;
const msg_el79 = doc3.getElementById("__xi-design-msg");
setTimeout((function () {
return msg_el79.focus();

}), 50);
doc3.getElementById("__xi-design-send").addEventListener("click", (function () {
return submit49(msg_el79.value.trim());

}));
return msg_el79.addEventListener("keydown", (function (e) {
e.stopPropagation();
if (squint_core.truth_(((e.key === "Enter") && squint_core.not(e.shiftKey)))) {
e.preventDefault();
return submit49(msg_el79.value.trim());
} else {
if ((e.key === "Escape")) {
e.preventDefault();
close_popover44();
update_hl43(null);
return show_toast48("Re-pick — click another element");
} else {
return null}};

}));

});
const spinner_html51 = (function (size) {
return `${"<span style=\"display:inline-block;width:"}${size??''}${"px;height:"}${size??''}${"px;"}${"border:2px solid "}${C2.border??''}${";border-top-color:"}${C2.accent??''}${";border-radius:50%;animation:__xiDesignSpin 0.7s linear infinite;\"></span>"}`;

});
const working_QMARK_52 = (function (a) {
const or__23542__auto__80 = (a.status === "running");
if (or__23542__auto__80) {
return or__23542__auto__80} else {
return (a.commit === "committing")};

});
const list_sig53 = (function (arr) {
return arr.map((function (a) {
return `${a.id??''}${":"}${a.status??''}${":"}${(() => {
const or__23542__auto__81 = a.commit;
if (squint_core.truth_(or__23542__auto__81)) {
return or__23542__auto__81} else {
return ""};

})()??''}`;

})).join("|");

});
const render_agents_btn54 = (function () {
const arr82 = (() => {
const or__23542__auto__83 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__83)) {
return or__23542__auto__83} else {
return []};

})();
const n84 = arr82.length;
if ((n84 === 0)) {
agents_btn39.style.display = "none";
return state8.agentsSig = null;
} else {
const busy85 = arr82.some((function (a) {
return working_QMARK_52(a);

}));
const sig86 = `${n84??''}${":"}${busy85??''}`;
agents_btn39.style.display = "flex";
if (!(sig86 === state8.agentsSig)) {
state8.agentsSig = sig86;
return agents_btn39.innerHTML = `${((squint_core.truth_(busy85)) ? (spinner_html51(13)) : (`${"<span style=\"color:"}${C2.accent??''}${";font-size:13px;\">✦</span>"}`))??''}${"<span style=\"font-family:"}${serif5}${";font-weight:600;\">"}${((squint_core.truth_(busy85)) ? ("Working") : ("Agents"))??''}${"</span>"}${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">"}${n84??''}${"</span>"}`;
};
};

});
const agent_row_html55 = (function (a) {
const status87 = a.status;
const commit88 = a.commit;
const right89 = (((status87 === "running")) ? (spinner_html51(12)) : ((((status87 === "error")) ? (`${"<span style=\"color:#c0392b;font-size:11.5px;\">error</span>"}`) : ((((commit88 === "committed")) ? (`${"<span style=\"color:"}${C2.accent??''}${";font-size:15px;line-height:1;\">✓</span>"}`) : ((((commit88 === "committing")) ? (spinner_html51(12)) : ((((commit88 === "error")) ? (`${"<span style=\"color:#c0392b;font-size:11.5px;\">commit failed</span>"}`) : ((((status87 === "done")) ? (`${"<button data-commit-id=\""}${esc3(a.id)??''}${"\" "}${"style=\"padding:4px 11px;border-radius:8px;border:1px solid "}${C2.border??''}${";background:"}${C2.accent??''}${";color:#fff;cursor:pointer;"}${"font-size:12px;font-weight:600;font-family:inherit;\">Commit</button>"}`) : ((((status87 === "stopped")) ? (`${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">stopped</span>"}`) : ((("else") ? ("") : (null))))))))))))))));
const dot90 = (((status87 === "running")) ? (C2.accent) : ((((status87 === "error")) ? ("#c0392b") : ((((status87 === "done")) ? ("#3a9d5d") : ((("else") ? (C2.textFaint) : (null))))))));
return `${"<div style=\"display:flex;align-items:center;gap:9px;padding:8px 2px;border-top:1px solid "}${C2.border??''}${";\">"}${"<span style=\"width:7px;height:7px;border-radius:50%;flex:none;background:"}${dot90??''}${";\"></span>"}${"<span style=\"flex:1;min-width:0;font-size:12.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;\">"}${esc3((() => {
const or__23542__auto__91 = a.label;
if (squint_core.truth_(or__23542__auto__91)) {
return or__23542__auto__91} else {
return "agent"};

})())??''}${"</span>"}${"<span style=\"flex:none;display:flex;align-items:center;min-height:22px;\">"}${right89??''}${"</span>"}${"</div>"}`;

});
const render_agents_list56 = (function () {
const temp__23127__auto__92 = state8.agentsPop;
if (squint_core.truth_(temp__23127__auto__92)) {
const pop93 = temp__23127__auto__92;
const arr94 = (() => {
const or__23542__auto__95 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__95)) {
return or__23542__auto__95} else {
return []};

})();
const sig96 = list_sig53(arr94);
const body97 = doc3.getElementById("__xi-design-agents-list");
if (squint_core.truth_((() => {
const and__23573__auto__98 = body97;
if (squint_core.truth_(and__23573__auto__98)) {
return !squint_core._EQ_(sig96, state8.agentsListSig)} else {
return and__23573__auto__98};

})())) {
state8.agentsListSig = sig96;
body97.innerHTML = (((0 === arr94.length)) ? (`${"<div style=\"color:"}${C2.textFaint??''}${";font-size:12.5px;padding:8px 2px;\">No design agents yet.</div>"}`) : (arr94.map((function (a) {
return agent_row_html55(a);

})).join("")));
for (let G__99 of squint_core.iterable(Array.from(body97.querySelectorAll("[data-commit-id]")))) {
const btn100 = G__99;
btn100.addEventListener("click", (function (e) {
e.stopPropagation();
return request_commit58(btn100.getAttribute("data-commit-id"));

}))
}
return null;
};
};

});
const render_agents57 = (function () {
render_agents_btn54();
return render_agents_list56();

});
const request_commit58 = (function (id) {
if (squint_core.truth_(window.__xiDesignCommitQueue)) {
} else {
window.__xiDesignCommitQueue = []};
window.__xiDesignCommitQueue.push(({"id": id, "ts": Date.now()}));
const arr101 = (() => {
const or__23542__auto__102 = window.__xiDesignAgents;
if (squint_core.truth_(or__23542__auto__102)) {
return or__23542__auto__102} else {
return []};

})();
arr101.forEach((function (a) {
if (squint_core._EQ_(a.id, id)) {
return a.commit = "committing";
};

}));
state8.agentsSig = null;
state8.agentsListSig = null;
return render_agents57();

});
const close_agents_pop59 = (function () {
const temp__23127__auto__103 = state8.agentsPop;
if (squint_core.truth_(temp__23127__auto__103)) {
const p104 = temp__23127__auto__103;
p104.remove();
state8.agentsPop = null;
return state8.agentsListSig = null;
};

});
const open_agents_pop60 = (function () {
const pop105 = doc3.createElement("div");
pop105.id = "__xi-design-agents-pop";
pop105.style.cssText = `${"position:fixed;right:16px;bottom:62px;z-index:2147483647;width:322px;"}${"max-height:60vh;overflow:auto;background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";border-radius:14px;padding:12px 14px;font-family:"}${sans6}${";"}${"box-shadow:0 8px 40px rgba(30,30,28,0.18),0 2px 8px rgba(30,30,28,0.08);"}`;
pop105.innerHTML = `${"<div style=\"display:flex;align-items:baseline;gap:7px;margin-bottom:4px;\">"}${"<span style=\"color:"}${C2.accent??''}${";font-size:14px;\">✦</span>"}${"<span style=\"font-family:"}${serif5}${";font-size:15px;font-weight:600;\">Design agents</span></div>"}${"<div id=\"__xi-design-agents-list\"></div>"}`;
root4.appendChild(pop105);
state8.agentsPop = pop105;
return render_agents_list56();

});
const toggle_agents_pop61 = (function () {
if (squint_core.truth_(state8.agentsPop)) {
return close_agents_pop59()} else {
return open_agents_pop60()};

});
style_pill42(false);
const sheet106 = doc3.createElement("style");
sheet106.id = "__xi-design-style";
sheet106.textContent = "@keyframes __xiDesignSpin{to{transform:rotate(360deg)}}";
root4.appendChild(sheet106);
dock38.appendChild(agents_btn39);
dock38.appendChild(pill37);
if (squint_core.truth_(window.__xiDesignCommitQueue)) {
} else {
window.__xiDesignCommitQueue = []};
window.__xiDesignRender = render_agents57;
render_agents57();
agents_btn39.addEventListener("click", (function (e) {
e.stopPropagation();
return toggle_agents_pop61();

}));
pill37.addEventListener("click", (function () {
return toggle_picking47();

}));
overlay36.addEventListener("mousemove", (function (e) {
if (squint_core.truth_(state8.selected)) {
return null} else {
overlay36.style.pointerEvents = "none";
const el107 = doc3.elementFromPoint(e.clientX, e.clientY);
overlay36.style.pointerEvents = "auto";
if (squint_core.truth_((() => {
const and__23573__auto__108 = el107;
if (squint_core.truth_(and__23573__auto__108)) {
const or__23542__auto__109 = squint_core.not(el107.id);
if (or__23542__auto__109) {
return or__23542__auto__109} else {
return !(0 === el107.id.indexOf("__xi-design"))};
} else {
return and__23573__auto__108};

})())) {
state8.hovered = el107;
return update_hl43(el107);
};
};

}));
overlay36.addEventListener("click", (function (e) {
e.preventDefault();
e.stopPropagation();
if (squint_core.truth_((() => {
const or__23542__auto__110 = state8.selected;
if (squint_core.truth_(or__23542__auto__110)) {
return or__23542__auto__110} else {
return squint_core.not(state8.hovered)};

})())) {
return null} else {
state8.selected = state8.hovered;
hl34.style.borderColor = C2.accent;
tip35.style.display = "none";
return open_popover50();
};

}));
const key_handler111 = (function key_handler (e) {
if (squint_core.not(window.__xiDesignActive)) {
return doc3.removeEventListener("keydown", key_handler, true)} else {
if (squint_core.truth_((() => {
const and__23573__auto__112 = e.ctrlKey;
if (squint_core.truth_(and__23573__auto__112)) {
return (squint_core.not(e.shiftKey) && (squint_core.not(e.altKey) && (squint_core.not(e.metaKey) && (() => {
const k113 = (() => {
const or__23542__auto__114 = e.key;
if (squint_core.truth_(or__23542__auto__114)) {
return or__23542__auto__114} else {
return ""};

})().toLowerCase();
const or__23542__auto__115 = (k113 === "i");
if (or__23542__auto__115) {
return or__23542__auto__115} else {
return (k113 === "b")};

})())))} else {
return and__23573__auto__112};

})())) {
e.preventDefault();
e.stopPropagation();
return toggle_picking47();
} else {
if (squint_core.truth_(((e.key === "Escape") && state8.picking))) {
e.preventDefault();
e.stopPropagation();
if (squint_core.truth_(state8.popover)) {
close_popover44();
return update_hl43(null);
} else {
return stop_picking45()};
} else {
return null}}};

});
doc3.addEventListener("keydown", key_handler111, true)};
