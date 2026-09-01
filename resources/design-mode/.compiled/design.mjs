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
const pill37 = mk8("__xi-design-pill", `${"position:fixed;bottom:16px;right:16px;z-index:2147483646;"}${"display:flex;align-items:center;gap:8px;cursor:pointer;user-select:none;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";padding:7px 14px;border-radius:999px;font:13px/1.4 "}${sans6}${";"}${"box-shadow:0 4px 24px rgba(30,30,28,0.14),0 1px 3px rgba(30,30,28,0.08);"}`);
const pill_idle38 = (function () {
return pill37.innerHTML = `${"<span style=\"color:"}${C2.accent??''}${";font-size:14px;\">✦</span>"}${"<span style=\"font-family:"}${serif5}${";font-weight:600;letter-spacing:0.01em;\">Design</span>"}${"<span style=\"color:"}${C2.textFaint??''}${";font-size:11.5px;\">Ctrl+I/B to pick</span>"}`;

});
const pill_picking39 = (function () {
return pill37.innerHTML = `${"<span style=\"font-size:14px;\">✦</span>"}${"<span style=\"font-family:"}${serif5}${";font-weight:600;\">Pick an element</span>"}${"<span style=\"opacity:0.75;font-size:11.5px;\">Esc to stop</span>"}`;

});
const style_pill40 = (function (picking_QMARK_) {
if (squint_core.truth_(picking_QMARK_)) {
pill37.style.background = C2.accent;
pill37.style.color = "#fff";
pill37.style.borderColor = C2.accent;
return pill_picking39();
} else {
pill37.style.background = C2.surface;
pill37.style.color = C2.text;
pill37.style.borderColor = C2.border;
return pill_idle38();
};

});
const update_hl41 = (function (el) {
if (squint_core.not(el)) {
hl34.style.display = "none";
return tip35.style.display = "none";
} else {
const r49 = el.getBoundingClientRect();
hl34.style.display = "block";
hl34.style.top = `${r49.top??''}px`;
hl34.style.left = `${r49.left??''}px`;
hl34.style.width = `${r49.width??''}px`;
hl34.style.height = `${r49.height??''}px`;
tip35.style.display = "block";
tip35.textContent = info5(el);
tip35.style.top = `${(((r49.top > 33)) ? ((r49.top - 28)) : ((r49.bottom + 5)))??''}px`;
return tip35.style.left = `${Math.max(5, r49.left)??''}px`;
};

});
const close_popover42 = (function () {
const temp__23127__auto__50 = state8.popover;
if (squint_core.truth_(temp__23127__auto__50)) {
const p51 = temp__23127__auto__50;
p51.remove();
state8.popover = null};
return state8.selected = null;

});
const stop_picking43 = (function () {
close_popover42();
state8.picking = false;
state8.hovered = null;
overlay36.style.display = "none";
update_hl41(null);
return style_pill40(false);

});
const start_picking44 = (function () {
state8.picking = true;
overlay36.style.display = "block";
return style_pill40(true);

});
const toggle_picking45 = (function () {
if (squint_core.truth_(state8.picking)) {
return stop_picking43()} else {
return start_picking44()};

});
const show_toast46 = (function (text) {
const temp__23127__auto__52 = doc3.getElementById("__xi-design-toast");
if (squint_core.truth_(temp__23127__auto__52)) {
const old53 = temp__23127__auto__52;
old53.remove()};
const toast54 = mk8("__xi-design-toast", `${"position:fixed;bottom:64px;right:16px;z-index:2147483646;"}${"pointer-events:none;display:flex;align-items:center;gap:8px;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";padding:9px 16px;border-radius:12px;"}${"font:13px/1.4 "}${sans6}${";box-shadow:0 4px 24px rgba(30,30,28,0.14);"}${"opacity:0;transform:translateY(4px);"}${"transition:opacity 180ms ease,transform 180ms ease;"}`);
toast54.innerHTML = `${"<span style=\"color:"}${C2.accent??''}${";\">✦</span>"}${esc3(text)??''}`;
requestAnimationFrame((function () {
toast54.style.opacity = "1";
return toast54.style.transform = "translateY(0)";

}));
return setTimeout((function () {
toast54.style.opacity = "0";
return setTimeout((function () {
return toast54.remove();

}), 250);

}), 2600);

});
const submit47 = (function (msg) {
const temp__23127__auto__55 = state8.selected;
if (squint_core.truth_(temp__23127__auto__55)) {
const el56 = temp__23127__auto__55;
window.__xiDesignQueue.push(capture7(el56, msg));
stop_picking43();
return show_toast46("Sent — a sub-agent is on it");
};

});
const open_popover48 = (function () {
const el57 = state8.selected;
const pop58 = doc3.createElement("div");
const r59 = el57.getBoundingClientRect();
const vw60 = window.innerWidth;
const vh61 = window.innerHeight;
const w62 = 360;
pop58.id = "__xi-design-pop";
pop58.style.cssText = `${"position:fixed;z-index:2147483647;width:"}${w62}${"px;"}${"background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";border-radius:14px;padding:14px;"}${"box-shadow:0 8px 40px rgba(30,30,28,0.18),0 2px 8px rgba(30,30,28,0.08);"}${"font-family:"}${sans6}${";"}`;
pop58.innerHTML = `${"<div style=\"display:flex;align-items:baseline;gap:7px;margin-bottom:10px;\">"}${"<span style=\"color:"}${C2.accent??''}${";font-size:14px;\">✦</span>"}${"<span style=\"font-family:"}${serif5}${";font-size:15px;font-weight:600;\">Describe the change</span>"}${"</div>"}${"<div style=\"font:11.5px/1.5 "}${mono7}${";padding:7px 10px;margin-bottom:10px;"}${"background:"}${C2.surfaceMuted??''}${";border-radius:8px;color:"}${C2.textMuted??''}${";word-break:break-all;max-height:56px;overflow:hidden;\">"}${esc3(info5(el57))??''}${"<br/><span style=\"color:"}${C2.textFaint??''}${";\">"}${esc3(selector4(el57))??''}${"</span></div>"}${"<textarea id=\"__xi-design-msg\" placeholder=\"e.g. more padding, warmer background…\""}${" style=\"width:100%;height:64px;resize:none;background:"}${C2.surface??''}${";color:"}${C2.text??''}${";border:1px solid "}${C2.border??''}${";border-radius:10px;padding:9px 11px;font-size:13.5px;font-family:inherit;"}${"line-height:1.45;outline:none;box-sizing:border-box;\"></textarea>"}${"<div style=\"display:flex;align-items:center;gap:8px;margin-top:10px;\">"}${"<span style=\"font-size:11px;color:"}${C2.textFaint??''}${";margin-right:auto;\">"}${"Enter to send · Esc to re-pick</span>"}${"<button id=\"__xi-design-send\" style=\"padding:7px 16px;border-radius:9px;border:none;"}${"background:"}${C2.accent??''}${";color:#fff;cursor:pointer;font-size:13px;"}${"font-weight:600;font-family:inherit;\">Send</button>"}${"</div>"}`;
root4.appendChild(pop58);
state8.popover = pop58;
const ph63 = pop58.getBoundingClientRect().height;
const left64 = Math.min(Math.max(8, r59.left), (vw60 - w62 - 8));
const top65 = ((((r59.bottom + 8 + ph63) < vh61)) ? ((r59.bottom + 8)) : (Math.max(8, (r59.top - ph63 - 8))));
pop58.style.left = `${left64??''}px`;
pop58.style.top = `${top65??''}px`;
const msg_el66 = doc3.getElementById("__xi-design-msg");
setTimeout((function () {
return msg_el66.focus();

}), 50);
doc3.getElementById("__xi-design-send").addEventListener("click", (function () {
return submit47(msg_el66.value.trim());

}));
return msg_el66.addEventListener("keydown", (function (e) {
e.stopPropagation();
if (squint_core.truth_(((e.key === "Enter") && squint_core.not(e.shiftKey)))) {
e.preventDefault();
return submit47(msg_el66.value.trim());
} else {
if ((e.key === "Escape")) {
e.preventDefault();
close_popover42();
update_hl41(null);
return show_toast46("Re-pick — click another element");
} else {
return null}};

}));

});
style_pill40(false);
pill37.addEventListener("click", (function () {
return toggle_picking45();

}));
overlay36.addEventListener("mousemove", (function (e) {
if (squint_core.truth_(state8.selected)) {
return null} else {
overlay36.style.pointerEvents = "none";
const el67 = doc3.elementFromPoint(e.clientX, e.clientY);
overlay36.style.pointerEvents = "auto";
if (squint_core.truth_((() => {
const and__23573__auto__68 = el67;
if (squint_core.truth_(and__23573__auto__68)) {
const or__23542__auto__69 = squint_core.not(el67.id);
if (or__23542__auto__69) {
return or__23542__auto__69} else {
return !(0 === el67.id.indexOf("__xi-design"))};
} else {
return and__23573__auto__68};

})())) {
state8.hovered = el67;
return update_hl41(el67);
};
};

}));
overlay36.addEventListener("click", (function (e) {
e.preventDefault();
e.stopPropagation();
if (squint_core.truth_((() => {
const or__23542__auto__70 = state8.selected;
if (squint_core.truth_(or__23542__auto__70)) {
return or__23542__auto__70} else {
return squint_core.not(state8.hovered)};

})())) {
return null} else {
state8.selected = state8.hovered;
hl34.style.borderColor = C2.accent;
tip35.style.display = "none";
return open_popover48();
};

}));
const key_handler71 = (function key_handler (e) {
if (squint_core.not(window.__xiDesignActive)) {
return doc3.removeEventListener("keydown", key_handler, true)} else {
if (squint_core.truth_((() => {
const and__23573__auto__72 = e.ctrlKey;
if (squint_core.truth_(and__23573__auto__72)) {
return (squint_core.not(e.shiftKey) && (squint_core.not(e.altKey) && (squint_core.not(e.metaKey) && (() => {
const k73 = (() => {
const or__23542__auto__74 = e.key;
if (squint_core.truth_(or__23542__auto__74)) {
return or__23542__auto__74} else {
return ""};

})().toLowerCase();
const or__23542__auto__75 = (k73 === "i");
if (or__23542__auto__75) {
return or__23542__auto__75} else {
return (k73 === "b")};

})())))} else {
return and__23573__auto__72};

})())) {
e.preventDefault();
e.stopPropagation();
return toggle_picking45();
} else {
if (squint_core.truth_(((e.key === "Escape") && state8.picking))) {
e.preventDefault();
e.stopPropagation();
if (squint_core.truth_(state8.popover)) {
close_popover42();
return update_hl41(null);
} else {
return stop_picking43()};
} else {
return null}}};

});
doc3.addEventListener("keydown", key_handler71, true)};
