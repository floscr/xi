var Tn=Object.defineProperty;var Nn=(t,e)=>{for(var r in e)Tn(t,r,{get:e[r],enumerable:!0})};import*as E from"squint-cljs/core.js";import*as n from"squint-cljs/core.js";import*as gt from"three";import*as c from"squint-cljs/core.js";import*as I from"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";var jn={d:11,f:7,e:10,j:3,a:14,i:4,b:13,g:6,h:5,c:12},Dn=["a","b","c","d","e","f","g","h","i","j"],On={d:"g",f:"e",e:"f",j:"a",a:"j",i:"b",b:"i",g:"d",h:"c",c:"h"},Hn={"T-":0,"T+":1,"B-":16,"B+":17},_e={full:63,half:30,mini:17},Ce="abcdefghijklmnopqrstuvwxyz",qt=function(t){return`"${t??""}"`},Sn=function(t){let e=`${t??""}`;return c.truth_(I.starts_with_QMARK_(e,":"))?c.subs(e,1):e},ue=function(t){return c.reduce((function(e,r){return e*10+I.index_of("0123456789",r)}),0,t)},le=function(t){return t<26?c.subs(Ce,t,t+1):`${le(c.quot(t,26)-1)??""}${c.subs(Ce,c.mod(t,26),c.mod(t,26)+1)}`},Cn=function(t){let e=c.truth_(c.string_QMARK_(t))?{type:t}:(()=>{let i=t;return c.truth_(i)?i:{}})(),r=(()=>{let i=c.get(e,"type");return c.truth_(i)?i:"half"})();if(c.truth_((()=>{let i=r==="perfboard";return i||r==="stripboard"})())){let i=(()=>{let q=c.get(e,"cols");return c.truth_(q)?q:30})(),_=(()=>{let q=c.get(e,"rows");return c.truth_(q)?q:20})(),s=c.mapv(le,c.range(_));return{"z-min":-2,color:(()=>{let q=c.get(e,"color");return c.truth_(q)?q:"#2f6b4c"})(),thick:.63,rows:c.into({},c.map_indexed((function(q,a){return[a,_-1-q]}),s)),cols:i,"x-max":i+1.2,"z-max":_+1,kind:r,rails:{},"row-order":s,"x-min":-2.2}}else if(c.get(_e,r)!=null){let i=r==="mini";return{"z-min":i?1.2:-1.6,color:(()=>{let _=c.get(e,"color");return c.truth_(_)?_:"#f3efe4"})(),thick:2.8,rows:jn,cols:(()=>{let _=c.get(e,"cols");return c.truth_(_)?_:c.get(_e,r)})(),split:c.boolean$(c.get(e,"split_rails")),"x-max":(()=>{let _=c.get(e,"cols");return c.truth_(_)?_:c.get(_e,r)})()+1.2,"z-max":i?15.8:18.6,kind:"breadboard",rails:i?{}:Hn,"row-order":Dn,"x-min":-2.2}}else return{error:`board ${qt(r)??""} is none of full, half, mini, perfboard, stripboard`}},X=function(t,e){if(c.truth_(c.string_QMARK_(e))){let r=I.trim(e),i=c.re_matches(/([tTbB])([+-])([0-9]+)/,r);if(c.truth_(i)){let _=i,s=`${I.upper_case(c.nth(_,1))??""}${c.nth(_,2)??""}`,q=ue(c.nth(_,3)),a=c.get(c.get(t,"rails"),s);if(c.truth_(a!=null&&1<=q&&q<=c.get(t,"cols")))return{key:`${s}${q??""}`,rail:s,col:q,x:q-1,z:a}}else{let _=c.re_matches(/([a-zA-Z]+)([0-9]+)/,r);if(c.truth_(_)){let s=_,q=I.lower_case(c.nth(s,1)),a=ue(c.nth(s,2)),l=c.get(c.get(t,"rows"),q);if(c.truth_(l!=null&&1<=a&&a<=c.get(t,"cols")))return{key:`${q??""}${a??""}`,row:q,col:a,x:a-1,z:l}}}}},Rt=function(t){return c.vec(c.lazy((function*(){for(let e of c.iterable(c.concat(c.get(t,"row-order"),c.keys(c.get(t,"rails"))))){let r=e;for(let i of c.iterable(c.range(1,c.get(t,"cols")+1)))yield X(t,`${r??""}${i??""}`)}return null})))},jt=function(t){let e=c.get(t,"rail");return c.truth_(e)?e:c.get(t,"row")},Pe=function(t,e,r,i){let _=X(t,e);if(_==null)return[];if(r==null)return c.vec(c.keep((function(s){return X(t,`${jt(_)??""}${c.get(_,"col")+s}`)}),c.range(c.max(i,1))));{let s=X(t,r);if(s==null)return[_];if(c._EQ_(jt(_),jt(s))){let q=c.get(s,"col")>=c.get(_,"col")?1:-1;return c.mapv((function(a){return X(t,`${jt(_)??""}${a??""}`)}),c.range(c.get(_,"col"),c.get(s,"col")+q,q))}else if(c._EQ_(c.get(_,"col"),c.get(s,"col"))){let q=c.min(c.get(_,"z"),c.get(s,"z")),a=c.max(c.get(_,"z"),c.get(s,"z")),l=c.sort_by("z",c.filter((function(d){return q<=c.get(d,"z")&&c.get(d,"z")<=a}),c.keep((function(d){return X(t,`${d??""}${c.get(_,"col")??""}`)}),c.concat(c.get(t,"row-order"),c.keys(c.get(t,"rails"))))));return c.vec(c.get(_,"z")<=c.get(s,"z")?l:c.reverse(l))}else return[_,s]}},Ln=function(t,e){return c.truth_(c.get(e,"rail"))?`${c.get(e,"rail")??""}${(c.truth_((()=>{let r=c.get(t,"split");return c.truth_(r)?c.get(e,"col")>c.get(t,"cols")/2:r})())?"2":"")??""}`:c.get(t,"kind")==="breadboard"?`${(c.get(e,"z")<8.5?"U":"L")??""}${c.get(e,"col")??""}`:c.get(t,"kind")==="stripboard"?`S${c.get(e,"row")??""}`:c.get(e,"key")},Le={resistor:["1","2"],diode:["anode","cathode"],led:["anode","cathode"],capacitor:["+","-"],button:["1","2"]},Vn=["button","capacitor","diode","dip","header","led","module","resistor"],Pn=function(t,e,r,i){let _=c.get(e,"pins");return c.truth_((()=>{let s=c.sequential_QMARK_(_);return c.truth_(s)?c.seq(_):s})())?c.reduce((function(s,q){let a=q,l=c.nth(a,0,null),d=c.nth(a,1,null),m=c.vec((()=>{let g=c.get(d,"names");return c.truth_(g)?g:[]})()),v=Pe(t,c.get(d,"from"),c.get(d,"to"),c.count(m));return c.truth_((()=>{let g=c.empty_QMARK_(v);return c.truth_(g)?g:c.get(d,"to")!=null&&X(t,c.get(d,"to"))==null})())&&c.swap_BANG_(r,c.conj,`${i??""}: pin row from ${qt(c.get(d,"from"))??""}${(c.truth_(c.get(d,"to"))?` to ${qt(c.get(d,"to"))??""}`:null)??""} is not on the board`),c.into(s,c.map_indexed((function(g,p){return{name:`${c.get(m,g,"")??""}`,hole:p,row:l}}),v))}),[],c.map_indexed(c.vector,_)):(c.swap_BANG_(r,c.conj,`${i??""}: a module needs pins, rows of {from, to, names}`),[])},In=function(t,e,r,i){let _=X(t,c.get(e,"at")),s=(()=>{let l=c.get(e,"pins");return c.truth_(l)?l:8})(),q=c.max(2,s-c.mod(s,2)),a=c.quot(q,2);if(c.not(_))return c.swap_BANG_(r,c.conj,`${i??""}: at ${qt(c.get(e,"at"))??""} is not a hole on this board`),[];{let l=(()=>{let m=c.get(e,"across");return c.truth_(m)?m:c.get(t,"kind")==="breadboard"?c.get(On,c.get(_,"row")):le(3+c.first(c.keep_indexed((function(v,g){if(c._EQ_(g,c.get(_,"row")))return v}),c.get(t,"row-order"))))})(),d=X(t,`${l??""}${c.get(_,"col")??""}`);if(c.not(d))return c.swap_BANG_(r,c.conj,`${i??""}: no row ${qt(l)??""} across from ${c.get(_,"key")??""}`),[];{let m=c.get(_,"z")>c.get(d,"z")?1:-1,v=c.vec((()=>{let f=c.get(e,"names");return c.truth_(f)?f:[]})()),g=c.mapv((function(f){return{name:`${c.get(v,f,f+1)??""}`,hole:X(t,`${c.get(_,"row")??""}${c.get(_,"col")+m*f}`),row:0}}),c.range(a)),p=c.mapv((function(f){return{name:`${c.get(v,q-1-f,q-f)??""}`,hole:X(t,`${c.get(d,"row")??""}${c.get(_,"col")+m*f}`),row:1}}),c.reverse(c.range(a)));return c.truth_(c.some((function(f){return c.get(f,"hole")==null}),g))&&c.swap_BANG_(r,c.conj,`${i??""}: its ${q??""} pins run off the board`),c.filterv((function(f){return c.get(f,"hole")!=null}),c.concat(g,p))}}},ie=function(t,e,r,i,_){let s=X(t,c.get(e,r));return c.truth_(s)||c.swap_BANG_(i,c.conj,`${_??""}: ${Sn(r)??""} ${qt(c.get(e,r))??""} is not a hole on this board`),s},Fn=function(t,e,r,i){let _=c.get(e,"type"),s=(()=>{let a=c.get(e,"id");if(c.truth_(a))return a;{let l=c.get(e,"label");if(c.truth_(l))return l;{let d=_;return c.truth_(d)?d:"part"}}})(),q=_==="module"?Pn(t,e,i,s):_==="dip"?In(t,e,i,s):_==="header"?(()=>{let a=c.vec((()=>{let d=c.get(e,"names");return c.truth_(d)?d:[]})()),l=Pe(t,c.get(e,"from"),c.get(e,"to"),c.count(a));return c.truth_(c.empty_QMARK_(l))&&ie(t,e,"from",i,s),c.vec(c.map_indexed((function(d,m){return{name:`${c.get(a,d,"")??""}`,hole:m,row:0}}),l))})():c.get(Le,_)!=null?(()=>{let a=c.truth_((()=>{let w=_==="led";return w||_==="diode"})())?["anode","cathode"]:["from","to"],l=c.nth(a,0,null),d=c.nth(a,1,null),m=c.get(e,l)!=null?l:"from",v=c.get(e,d)!=null?d:"to",g=ie(t,e,m,i,s),p=ie(t,e,v,i,s),f=c.get(Le,_),b=c.nth(f,0,null),x=c.nth(f,1,null);return c.vec(c.concat(c.truth_(g)?[{name:b,hole:g,row:0}]:null,c.truth_(p)?[{name:x,hole:p,row:0}]:null))})():(c.swap_BANG_(i,c.conj,`${s??""}: type ${qt(_)??""} is none of ${I.join(", ",Vn)??""}`),[]);return{key:(()=>{let a=c.get(e,"id");return c.truth_(a)?a:`part-${r+1}`})(),id:c.get(e,"id"),type:_,label:(()=>{let a=c.get(e,"label");if(c.truth_(a))return a;{let l=c.get(e,"id");return c.truth_(l)?l:""}})(),spec:e,pins:q}},Dt=function(t,e){let r=`${e??""}`,i=I.index_of(r,".");if(c.truth_((()=>{let _=i;return c.truth_(_)?i>0:_})())){let _=c.subs(r,0,i),s=c.subs(r,i+1),q=c.some((function(a){if(_===c.get(a,"id"))return a}),t);if(c.truth_(q)){let a=c.some((function(l){if(c._EQ_(I.lower_case(s),I.lower_case(c.get(l,"name"))))return l}),c.get(q,"pins"));if(c.truth_(a))return a;if(c.truth_(c.re_matches(/[0-9]+/,s))){let l=ue(s);if(c.truth_(1<=l&&l<=c.count(c.get(q,"pins"))))return c.nth(c.get(q,"pins"),l-1)}}}},Ve=function(t,e,r,i,_){if(c.truth_(c.map_QMARK_(r)))return{off:`${(()=>{let s=c.get(r,"label");return c.truth_(s)?s:"?"})()??""}`,side:c.get(r,"side")};{let s=X(t,r);return c.truth_(s)?{hole:s}:c.truth_(Dt(e,r))?{hole:c.get(Dt(e,r),"hole"),ref:`${r??""}`}:(c.swap_BANG_(_,c.conj,`${i??""}: ${qt(r)??""} is neither a hole, a part pin (ID.PIN) nor {label}`),null)}},ce=function(t,e){let r=e;for(;;){let i=c.get(t,r);if(c.truth_(i!=null&&!c._EQ_(i,r))){r=i;continue}else return r;break}},ae=function(t,e){return c.truth_(c.get(e,"off"))?`off:${c.get(e,"off")??""}`:Ln(t,c.get(e,"hole"))},Ie=function(t){let e=c.atom([]),r=Cn(c.get(t,"board"));if(c.truth_(c.get(r,"error")))return{board:r,parts:[],wires:[],roots:{},problems:[c.get(r,"error")]};{let i=c.vec(c.map_indexed((function(a,l){return Fn(r,l,a,e)}),c.truth_(c.sequential_QMARK_(c.get(t,"parts")))?c.get(t,"parts"):[])),_=c.vec(c.keep_indexed((function(a,l){let d=`wire ${(()=>{let g=c.get(l,"id");return c.truth_(g)?g:`#${a+1}`})()??""}`,m=Ve(r,i,c.get(l,"from"),`${d} from`,e),v=Ve(r,i,c.get(l,"to"),`${d} to`,e);return c.truth_((()=>{let g=m==null;return g||v==null})())?null:c.truth_((()=>{let g=c.get(m,"off");return c.truth_(g)?c.get(v,"off"):g})())?(c.swap_BANG_(e,c.conj,`${d}: both ends are off the board`),null):{key:(()=>{let g=c.get(l,"id");return c.truth_(g)?g:`wire-${a+1}`})(),id:c.get(l,"id"),label:(()=>{let g=c.get(l,"label");return c.truth_(g)?g:""})(),spec:l,from:c.truth_(c.get(m,"off"))?v:m,to:c.truth_(c.get(m,"off"))?m:v}}),c.truth_(c.sequential_QMARK_(c.get(t,"wires")))?c.get(t,"wires"):[])),s=c.reduce((function(a,l){let d=ce(a,ae(r,c.get(l,"from"))),m=ce(a,ae(r,c.get(l,"to")));return c._EQ_(d,m)?a:c.assoc(a,d,m)}),{},_),q=c.into({},c.map((function(a){return[a,ce(s,a)]}),c.keys(s)));return{board:r,parts:i,wires:_,roots:q,problems:c.deref(e)}}},it=function(t,e){let r=ae(c.get(t,"board"),e),i=c.get(c.get(t,"roots"),r);return c.truth_(i)?i:r},dt=function(t){return c.truth_(c.sequential_QMARK_(t))?c.mapv(c.str,t):c.vec(c.remove(I.blank_QMARK_,I.split(`${(()=>{let e=t;return c.truth_(e)?e:""})()??""}`,/ /)))};import*as h from"squint-cljs/core.js";import*as W from"squint-cljs/core.js";import*as Ot from"three";var Ht="system-ui, -apple-system, Segoe UI, sans-serif",Wn=function(t){let e=new Ot.CanvasTexture(t);return e.colorSpace=Ot.SRGBColorSpace,e.anisotropy=8,e},Qt=function(t,e,r){let i=document.createElement("canvas");return i.width=Math.round(t),i.height=Math.round(e),r(i.getContext("2d")),Wn(i)},St=function(t,e,r,i,_,s){t.font=`600 ${r??""}px ${Ht??""}`;let q=t.measureText(e).width,a=r*.3,l=r*1.25,d=(()=>{switch(i){case"right":return 0-q-a*2;case"center":return 0-q/2-a;default:return 0}})();return W.truth_(s)&&(t.fillStyle=s,t.beginPath(),t.roundRect(d,l/-2,q+a*2,l,l/2),t.fill()),t.fillStyle=_,t.textBaseline="middle",t.textAlign="left",t.fillText(e,d+a,r*.05)},We=new Map,qe=function(t,e){let r=e,i=W.truth_(W.sequential_QMARK_(r))?W.truth_(W.vector_QMARK_(r))?r:W.seq_to_map_for_destructuring(r):r,_=W.get(i,"fg"),s=W.get(i,"bg"),q=(()=>{let m=_;return W.truth_(m)?m:"#1d1d1f"})(),a=(()=>{let m=s;return W.truth_(m)?m:"#ffffffee"})(),l=`${t??""}|${q??""}|${a??""}`,d=We.get(l);if(W.truth_(d))return d;{let v=document.createElement("canvas").getContext("2d"),g=v.font=`600 64px ${Ht??""}`,p=Math.ceil(v.measureText(t).width+64*.8),f=Math.ceil(64*1.35),b={texture:Qt(p,f,(function(x){return x.translate(p/2,f/2),St(x,t,64,"center",q,a)})),aspect:p/f};return We.set(l,b),b}};var ct=function(t,e,r,i,_){return t.fillStyle=_,t.beginPath(),t.arc(e,r,i,0,2*Math.PI),t.fill()},tt=function(t,e,r,i,_,s){return t.font=`600 ${_??""}px ${Ht??""}`,t.fillStyle=s,t.textAlign="center",t.textBaseline="middle",t.fillText(e,r,i)},Ye=function(t,e,r,i,_,s,q,a){let l=_>i?1:-1;return t.save(),t.translate(r,i+l*s*.85),t.rotate(Math.PI/-2),St(t,e,s,l>0?"right":"left",q,a),t.restore()},de=function(t,e){let r=h.get(t,"board"),i=h.get(r,"kind")==="breadboard",_=h.max(16,h.min(48,Math.floor(4096/(h.get(r,"x-max")-h.get(r,"x-min"))))),s=(h.get(r,"x-max")-h.get(r,"x-min"))*_,q=(h.get(r,"z-max")-h.get(r,"z-min"))*_,a=(function(g){return(g-h.get(r,"x-min"))*_}),l=(function(g){return(h.truth_(e)?h.get(r,"z-max")-g:g-h.get(r,"z-min"))*_}),d=(function(g,p){return[h.min(l(g),l(p)),Math.abs(l(p)-l(g))]}),m=i?"#8d8572":"#ffffffb0",v=Rt(r);return Qt(s,q,(function(g){if(g.fillStyle=h.truth_(i&&e)?"#ebe6d6":h.get(r,"color"),g.fillRect(0,0,s,q),i){if(!h.truth_(e)){let p=d(8.05,8.95),f=h.nth(p,0,null),b=h.nth(p,1,null);g.fillStyle="#00000012",g.fillRect(0,f,s,b)}for(let p of h.iterable([["T-",-.65,"#1e88e5"],["T+",1.65,"#e53935"],["B-",15.35,"#1e88e5"],["B+",17.65,"#e53935"]])){let f=p,b=h.nth(f,0,null),x=h.nth(f,1,null),w=h.nth(f,2,null);if(h.get(h.get(r,"rails"),b)!=null){let z=d(x-.05,x+.05),Q=h.nth(z,0,null),N=h.nth(z,1,null),T=h.subs(b,1)==="+"?"+":"\u2212";g.fillStyle=w,g.fillRect(a(-.8),Q,a(h.get(r,"cols")-.2)-a(-.8),N),tt(g,T,a(-1.4),l(h.get(h.get(r,"rails"),b)),.75*_,w),tt(g,T,a(h.get(r,"cols")+.4),l(h.get(h.get(r,"rails"),b)),.75*_,w)}}if(h.truth_(e)){g.fillStyle="#c4c8cd",g.strokeStyle="#9ba1a8",g.lineWidth=_*.04;let p=(function(f,b,x,w){let z=d(x,w),Q=h.nth(z,0,null),N=h.nth(z,1,null);return g.beginPath(),g.roundRect(a(f),Q,a(b)-a(f),N,_*.2),g.fill(),g.stroke()});for(let f of h.iterable(h.range(1,h.get(r,"cols")+1))){let b=f;p(b-1.28,b-.72,2.6,7.4),p(b-1.28,b-.72,9.6,14.4)}for(let f of h.iterable(h.vals(h.get(r,"rails")))){let b=f;p(-.4,h.get(r,"cols")-.6,b-.28,b+.28)}}for(let p of h.iterable(v)){let f=p;h.truth_(e)?ct(g,a(h.get(f,"x")),l(h.get(f,"z")),.1*_,"#6d727a"):(g.fillStyle="#3b3832",g.fillRect(a(h.get(f,"x"))-_*.19,l(h.get(f,"z"))-_*.19,_*.38,_*.38))}for(let p of h.iterable(h.range(1,h.get(r,"cols")+1))){let f=p;h.truth_((()=>{let b=f===1;if(b)return b;{let x=h.get(r,"cols")<=30;return x||h.mod(f,5)===0}})())&&(tt(g,`${f??""}`,a(f-1),l(2.05),.42*_,m),tt(g,`${f??""}`,a(f-1),l(15),.42*_,m))}for(let p of h.iterable(h.get(r,"row-order"))){let f=p;tt(g,f,a(-1.1),l(h.get(h.get(r,"rows"),f)),.48*_,m),tt(g,f,a(h.get(r,"cols")+.1),l(h.get(h.get(r,"rows"),f)),.48*_,m)}}else{if(h.truth_((()=>{let p=e;return h.truth_(p)?h.get(r,"kind")==="stripboard":p})())){g.fillStyle="#c99a52";for(let p of h.iterable(h.vals(h.get(r,"rows")))){let f=p,b=d(f-.4,f+.4),x=h.nth(b,0,null),w=h.nth(b,1,null);g.fillRect(a(-.5),x,a(h.get(r,"cols")-.5)-a(-.5),w)}}for(let p of h.iterable(v)){let f=p;ct(g,a(h.get(f,"x")),l(h.get(f,"z")),.34*_,"#c99a52"),ct(g,a(h.get(f,"x")),l(h.get(f,"z")),.13*_,"#1b1b1b")}for(let p of h.iterable(h.range(1,h.get(r,"cols")+1))){let f=p;h.truth_((()=>{let b=f===1;if(b)return b;{let x=h.get(r,"cols")<=30;return x||h.mod(f,5)===0}})())&&(tt(g,`${f??""}`,a(f-1),l(-1.1),.42*_,m),tt(g,`${f??""}`,a(f-1),l(h.get(r,"z-max")-.9),.42*_,m))}for(let p of h.iterable(h.get(r,"row-order"))){let f=p;tt(g,f,a(-1.3),l(h.get(h.get(r,"rows"),f)),.48*_,m),tt(g,f,a(h.get(r,"cols")+.3),l(h.get(h.get(r,"rows"),f)),.48*_,m)}}if(h.truth_(e)){for(let p of h.iterable(h.get(t,"parts"))){let f=p;if(h.truth_(h.seq(h.get(f,"pins")))){let b=h.reduce(h._PLUS_,h.map((function(x){return h.get(h.get(x,"hole"),"z")}),h.get(f,"pins")))/h.count(h.get(f,"pins"));for(let x of h.iterable(h.get(f,"pins"))){let w=x,z=h.get(w,"hole");ct(g,a(h.get(z,"x")),l(h.get(z,"z")),.3*_,"#f2c94c"),ct(g,a(h.get(z,"x")),l(h.get(z,"z")),.1*_,"#7a5d12"),h.truth_((()=>{let Q=h.seq(h.get(w,"name"));return h.truth_(Q)?h.contains_QMARK_(new Set(["dip","module","header"]),h.get(f,"type")):Q})())&&Ye(g,h.get(w,"name"),a(h.get(z,"x")),l(h.get(z,"z")),l(b),_*.36,"#ffffff","#202124d9")}}}return null}}))},Je=function(t,e,r){let _=(h.get(e,"x1")-h.get(e,"x0"))*48,s=(h.get(e,"z1")-h.get(e,"z0"))*48,q=(function(d){return(d-h.get(e,"x0"))*48}),a=(function(d){return(d-h.get(e,"z0"))*48}),l=(h.get(e,"z0")+h.get(e,"z1"))/2;return Qt(_,s,(function(d){d.fillStyle=r,d.fillRect(0,0,_,s),d.strokeStyle="#ffffff30",d.lineWidth=48*.08,d.strokeRect(48*.08,48*.08,_-48*.16,s-48*.16);for(let m of h.iterable(h.get(t,"pins"))){let v=m,g=h.get(v,"hole");ct(d,q(h.get(g,"x")),a(h.get(g,"z")),48*.3,"#d4af37"),ct(d,q(h.get(g,"x")),a(h.get(g,"z")),48*.12,"#5a4510"),h.truth_(h.seq(h.get(v,"name")))&&Ye(d,h.get(v,"name"),q(h.get(g,"x")),a(h.get(g,"z")),a(l),48*.36,"#ffffffee",null)}for(let m of h.iterable((()=>{let v=h.get(h.get(t,"spec"),"marks");return h.truth_(v)?v:[]})())){let v=m,g=(()=>{let f=h.get(v,"side");return h.truth_(f)?f:"left"})();switch(d.save(),g){case"left":d.translate(48*.6,s/2),d.rotate(Math.PI/2);break;case"right":d.translate(_-48*.6,s/2),d.rotate(Math.PI/-2);break;case"top":d.translate(_/2,48*.6);break;default:d.translate(_/2,s-48*.6)}St(d,(()=>{let f=h.get(v,"text");return h.truth_(f)?f:""})(),48*.42,"center","#202124","#ffffffcc"),d.restore()}if(h.truth_(h.seq(h.get(t,"label")))){let m=h.min(52.800000000000004,_*.8/h.max(1,h.count(h.get(t,"label"))*.58),s*.3);return tt(d,h.get(t,"label"),_/2,s/2,m,"#ffffffe8")}}))},Ze=function(t,e,r){let _=(h.get(e,"x1")-h.get(e,"x0"))*48,s=(h.get(e,"z1")-h.get(e,"z0"))*48,q=h.get(h.first(h.get(t,"pins")),"hole");return Qt(_,s,(function(a){if(a.fillStyle=(()=>{let l=h.get(h.get(t,"spec"),"color");return h.truth_(l)?l:"#26272b"})(),a.fillRect(0,0,_,s),ct(a,r>0?0:_,s/2,48*.42,"#121214"),ct(a,(h.get(q,"x")-h.get(e,"x0"))*48+r*48*.05,s-48*.55,48*.18,"#5c5e66"),h.truth_(h.seq(h.get(t,"label")))){let l=h.min(38.400000000000006,_*.8/h.max(1,h.count(h.get(t,"label"))*.6));return tt(a,h.get(t,"label"),_/2,s/2,l,"#e8e8e8")}}))},Ct=["#111111","#7b4a24","#d32f2f","#f57c00","#fbc02d","#388e3c","#1e63c6","#7b1fa2","#8a8a8a","#f5f5f5"],Xe={blue:6,white:9,purple:7,grey:8,orange:3,violet:7,gray:8,yellow:4,green:5,red:2,brown:1,black:0},tn=function(t){if(h.truth_(h.vector_QMARK_(h.get(t,"bands"))))return h.mapv((function(e){return h.get(Xe,e)!=null?h.nth(Ct,h.get(Xe,e)):e==="gold"?"#c9a227":e==="silver"?"#b0b0b0":e}),h.get(t,"bands"));if((()=>{let e=h.get(t,"ohms");return h.truth_(e)?e:0})()>0){let e=h.get(t,"ohms"),r=Math.floor(Math.log10(e))-1,i=Math.round(e/Math.pow(10,r)),_=i>=100?[Math.round(i/10),r+1]:[i,r],s=h.nth(_,0,null),q=h.nth(_,1,null),a=q>=0?h.nth(Ct,q):q===-1?"#c9a227":"#b0b0b0";return[h.nth(Ct,Math.floor(s/10)),h.nth(Ct,h.mod(s,10)),a,"#c9a227"]}else return[]};import*as o from"squint-cljs/core.js";var zt={};Nn(zt,{between:()=>Bt,deftag_BANG_:()=>ut,dispose_BANG_:()=>rn,expand:()=>Lt,find_prop:()=>Pt,render_BANG_:()=>Kt,shown_QMARK_:()=>me});import*as u from"squint-cljs/core.js";import*as A from"three";var fe=new Map,ut=function(t,e){return fe.set(t,e)},he=function(t){return new A.Vector3(u.nth(t,0),u.nth(t,1),u.nth(t,2))},Bt=function(t,e){let r=he(t),i=he(e),_=new A.Vector3().subVectors(i,r).normalize(),s=new A.Quaternion().setFromUnitVectors(new A.Vector3(0,1,0),_);return{position:[(u.nth(t,0)+u.nth(e,0))/2,(u.nth(t,1)+u.nth(e,1))/2,(u.nth(t,2)+u.nth(e,2))/2],quaternion:[s.x,s.y,s.z,s.w],length:r.distanceTo(i)}},Xn=function(t,e){if(!u.truth_(u.get(e,"manual-transform"))){let i=(()=>{let q=u.get(e,"position");return u.truth_(q)?q:[0,0,0]})();t.position.set(u.nth(i,0),u.nth(i,1),u.nth(i,2));let _=u.get(e,"quaternion");if(u.truth_(_)){let q=_;t.quaternion.set(u.nth(q,0),u.nth(q,1),u.nth(q,2),u.nth(q,3))}else{let q=(()=>{let a=u.get(e,"rotation");return u.truth_(a)?a:[0,0,0]})();t.rotation.set(u.nth(q,0),u.nth(q,1),u.nth(q,2))}let s=u.get(e,"scale");u.truth_(u.number_QMARK_(s))?t.scale.setScalar(s):u.truth_(s)?t.scale.set(u.nth(s,0),u.nth(s,1),u.nth(s,2)):t.scale.setScalar(1)}t.visible=u.not(u.false_QMARK_(u.get(e,"visible"))),t.renderOrder=(()=>{let i=u.get(e,"render-order");return u.truth_(i)?i:0})(),t.userData.props=e;let r=u.get(e,"user-data");if(u.truth_(r)){let i=r;for(let _ of u.iterable(Object.keys(i))){let s=_;t.userData[s]=i[s]}return null}},Et=function(t){let e=u.get(t,"kind");return u.truth_(e)?e:"standard"},en=function(t){return Et(t)==="basic"?new A.MeshBasicMaterial:new A.MeshStandardMaterial},ge=function(t,e){let r=Et(e)==="basic",i=u.boolean$(u.get(e,"transparent")),_=(()=>{let s=u.get(e,"map");return u.truth_(s)?s:null})();return t.color.set((()=>{let s=u.get(e,"color");return u.truth_(s)?s:"#ffffff"})()),r||(t.roughness=(()=>{let s=u.get(e,"roughness");return u.truth_(s)?s:.6})(),t.metalness=(()=>{let s=u.get(e,"metalness");return u.truth_(s)?s:0})(),t.emissive.set((()=>{let s=u.get(e,"emissive");return u.truth_(s)?s:"#000000"})()),t.emissiveIntensity=(()=>{let s=u.get(e,"emissive-intensity");return u.truth_(s)?s:1})()),u.truth_((()=>{let s=!u._EQ_(_,t.map);return s||!u._EQ_(i,t.transparent)})())&&(t.needsUpdate=!0),t.map=_,t.transparent=i,t.opacity=(()=>{let s=u.get(e,"opacity");return u.truth_(s)?s:1})(),t.side=u.get(e,"side")==="double"?A.DoubleSide:A.FrontSide,t.depthWrite=u.not(u.false_QMARK_(u.get(e,"depth-write"))),t.depthTest=u.not(u.false_QMARK_(u.get(e,"depth-test"))),t.polygonOffset=u.boolean$(u.get(e,"polygon-offset")),t.polygonOffsetFactor=(()=>{let s=u.get(e,"polygon-offset");return u.truth_(s)?s:0})(),t},Yn=function(t,e){let r=u.mapv(he,t);return e==="bezier"?new A.CubicBezierCurve3(u.nth(r,0),u.nth(r,1),u.nth(r,2),u.nth(r,3)):new A.CatmullRomCurve3(r)},Vt={ring:[["inner","outer","segments"],(function(t){return new A.RingGeometry((()=>{let e=u.get(t,"inner");return u.truth_(e)?e:.3})(),(()=>{let e=u.get(t,"outer");return u.truth_(e)?e:.5})(),(()=>{let e=u.get(t,"segments");return u.truth_(e)?e:24})())})],capsule:[["radius","length"],(function(t){return new A.CapsuleGeometry((()=>{let e=u.get(t,"radius");return u.truth_(e)?e:.5})(),(()=>{let e=u.get(t,"length");return u.truth_(e)?e:1})(),6,16)})],box:[["size"],(function(t){let e=(()=>{let r=u.get(t,"size");return u.truth_(r)?r:[1,1,1]})();return new A.BoxGeometry(u.nth(e,0),u.nth(e,1),u.nth(e,2))})],"torus-knot":[["radius","tube"],(function(t){return new A.TorusKnotGeometry((()=>{let e=u.get(t,"radius");return u.truth_(e)?e:.35})(),(()=>{let e=u.get(t,"tube");return u.truth_(e)?e:.1})(),96,12)})],plane:[["size"],(function(t){let e=(()=>{let r=u.get(t,"size");return u.truth_(r)?r:[1,1]})();return new A.PlaneGeometry(u.nth(e,0),u.nth(e,1))})],tube:[["points","curve","radius","segments"],(function(t){return new A.TubeGeometry(Yn(u.get(t,"points"),u.get(t,"curve")),(()=>{let e=u.get(t,"segments");return u.truth_(e)?e:32})(),(()=>{let e=u.get(t,"radius");return u.truth_(e)?e:.1})(),8)})],sphere:[["radius","segments","theta-length"],(function(t){return new A.SphereGeometry((()=>{let e=u.get(t,"radius");return u.truth_(e)?e:.5})(),(()=>{let e=u.get(t,"segments");return u.truth_(e)?e:24})(),(()=>{let e=u.get(t,"segments");return u.truth_(e)?e:24})()/2,0,2*Math.PI,0,(()=>{let e=u.get(t,"theta-length");return u.truth_(e)?e:Math.PI})())})],cylinder:[["radius","radius-top","radius-bottom","height","segments","open-ended","theta-start","theta-length"],(function(t){return new A.CylinderGeometry((()=>{let e=u.get(t,"radius-top");if(u.truth_(e))return e;{let r=u.get(t,"radius");return u.truth_(r)?r:.5}})(),(()=>{let e=u.get(t,"radius-bottom");if(u.truth_(e))return e;{let r=u.get(t,"radius");return u.truth_(r)?r:.5}})(),(()=>{let e=u.get(t,"height");return u.truth_(e)?e:1})(),(()=>{let e=u.get(t,"segments");return u.truth_(e)?e:24})(),1,u.boolean$(u.get(t,"open-ended")),(()=>{let e=u.get(t,"theta-start");return u.truth_(e)?e:0})(),(()=>{let e=u.get(t,"theta-length");return u.truth_(e)?e:2*Math.PI})())})],torus:[["radius","tube"],(function(t){return new A.TorusGeometry((()=>{let e=u.get(t,"radius");return u.truth_(e)?e:.5})(),(()=>{let e=u.get(t,"tube");return u.truth_(e)?e:.15})(),16,48)})],cone:[["radius","height","segments"],(function(t){return new A.ConeGeometry((()=>{let e=u.get(t,"radius");return u.truth_(e)?e:.5})(),(()=>{let e=u.get(t,"height");return u.truth_(e)?e:1})(),(()=>{let e=u.get(t,"segments");return u.truth_(e)?e:24})())})]},nn=function(t,e){return JSON.stringify(u.mapv((function(r){return u.get(e,r)}),u.first(u.get(Vt,t))))},Jn=function(t,e){let r=t==="mesh"?u.get(e,"geometry"):u.second(u.get(Vt,t))(e),i=(()=>{let s=u.get(e,"material");return u.truth_(s)?s:{}})(),_=new A.Mesh(r,ge(en(i),i));return _.userData.geometryKey=t==="mesh"?null:nn(t,e),_},Zn=function(t,e,r){if(e==="mesh")u._EQ_(t.geometry,u.get(r,"geometry"))||(t.geometry=u.get(r,"geometry"));else{let _=nn(e,r);u._EQ_(_,t.userData.geometryKey)||(t.geometry.dispose(),t.geometry=u.second(u.get(Vt,e))(r),t.userData.geometryKey=_)}let i=(()=>{let _=u.get(r,"material");return u.truth_(_)?_:{}})();return u._EQ_(Et(i),t.userData.materialKind)?ge(t.material,i):(t.material.dispose(),t.material=ge(en(i),i)),t.userData.materialKind=Et((()=>{let _=u.get(r,"material");return u.truth_(_)?_:{}})())};for(let t of u.iterable(u.conj(u.vec(u.keys(Vt)),"mesh"))){let e=t;ut(e,{create:(function(r){let i=Jn(e,r);return i.userData.materialKind=Et((()=>{let _=u.get(r,"material");return u.truth_(_)?_:{}})()),i}),update:(function(r,i){return Zn(r,e,i)})})}ut("group",{create:(function(t){return new A.Group})});ut("object",{create:(function(t){let e=u.get(t,"object");return e.userData.external=!0,e})});var tr=function(t){let e=u.get(t,"texture");return u.truth_(e)?e:u.get(qe((()=>{let r=u.get(t,"text");return u.truth_(r)?r:""})(),t),"texture")};ut("sprite",{create:(function(t){return new A.Sprite(new A.SpriteMaterial({map:tr(t),transparent:!0,depthTest:u.not(u.false_QMARK_(u.get(t,"depth-test")))}))}),update:(function(t,e){let r=u.truth_(u.get(e,"texture"))?{texture:u.get(e,"texture"),aspect:(()=>{let a=u.get(e,"aspect");return u.truth_(a)?a:1})()}:qe((()=>{let a=u.get(e,"text");return u.truth_(a)?a:""})(),e),i=u.truth_(u.sequential_QMARK_(r))?u.truth_(u.vector_QMARK_(r))?r:u.seq_to_map_for_destructuring(r):r,_=u.get(i,"texture"),s=u.get(i,"aspect"),q=(()=>{let a=u.get(e,"size");return u.truth_(a)?a:1})();return u._EQ_(_,t.material.map)||(t.material.map=_,t.material.needsUpdate=!0),t.scale.set(q*s,q,1)})});ut("hemisphere-light",{create:(function(t){return new A.HemisphereLight}),update:(function(t,e){return t.color.set((()=>{let r=u.get(e,"sky");return u.truth_(r)?r:"#ffffff"})()),t.groundColor.set((()=>{let r=u.get(e,"ground");return u.truth_(r)?r:"#8a8a8a"})()),t.intensity=(()=>{let r=u.get(e,"intensity");return u.truth_(r)?r:1})()})});ut("directional-light",{create:(function(t){return new A.DirectionalLight}),update:(function(t,e){return t.color.set((()=>{let r=u.get(e,"color");return u.truth_(r)?r:"#ffffff"})()),t.intensity=(()=>{let r=u.get(e,"intensity");return u.truth_(r)?r:1})()})});ut("ambient-light",{create:(function(t){return new A.AmbientLight}),update:(function(t,e){return t.color.set((()=>{let r=u.get(e,"color");return u.truth_(r)?r:"#ffffff"})()),t.intensity=(()=>{let r=u.get(e,"intensity");return u.truth_(r)?r:1})()})});ut("grid",{create:(function(t){let e=new A.GridHelper((()=>{let r=u.get(t,"size");return u.truth_(r)?r:20})(),(()=>{let r=u.get(t,"divisions");return u.truth_(r)?r:20})(),(()=>{let r=u.get(t,"center-color");return u.truth_(r)?r:"#888888"})(),(()=>{let r=u.get(t,"color");return u.truth_(r)?r:"#444444"})());return e.material.transparent=!0,e.material.opacity=(()=>{let r=u.get(t,"opacity");return u.truth_(r)?r:.35})(),e})});var er=function(t){let e=u.vector_QMARK_(t);if(u.truth_(e)){let r=u.first(t),i=u.string_QMARK_(r);return u.truth_(i)?i:u.fn_QMARK_(r)}else return e},nr=function(t){return t!=null&&u.not(u.string_QMARK_(t))&&u.fn_QMARK_(t[Symbol.iterator])},Lt=function(t){return u.truth_(er(t))?u.truth_(u.fn_QMARK_(u.first(t)))?Lt(u.apply(u.first(t),u.rest(t))):[t]:u.truth_(nr(t))?u.vec(u.mapcat(Lt,t)):[]},rr=function(t){let e=u.second(t),r=u.map_QMARK_(e);return[u.first(t),u.truth_(r)?e:{},u.truth_(r)?u.drop(2,t):u.drop(1,t)]},rn=function(t){return t.traverse((function(e){if(u.truth_(e.userData.external))return null;if(u.truth_(e.geometry)&&e.geometry.dispose(),u.truth_(e.material))return e.material.dispose()}))},or=function(t,e){let r=fe.get(t);if(!u.truth_(r))throw new Error(`three.core: no tag ${t??""}`);let i=u.get(r,"create")(e);return i.userData.tag=t,i},Kt=function(t,e){let r=(()=>{let _=t.userData.kids;return u.truth_(_)?_:new Map})(),i=new Map;for(let _ of u.iterable(u.map_indexed(u.vector,Lt(e)))){let s=_,q=u.nth(s,0,null),a=u.nth(s,1,null),l=rr(a),d=u.nth(l,0,null),m=u.nth(l,1,null),v=u.nth(l,2,null),g=`${d??""}|${(u.get(m,"key")!=null?u.get(m,"key"):`#${q??""}`)??""}`,p=r.get(g),f=(()=>{let x=p;return u.truth_(x)?x:or(d,m)})();r.delete(g),Xn(f,m);let b=u.get(fe.get(d),"update");u.truth_(b)&&b(f,m),u._EQ_(f.parent,t)||t.add(f),i.set(g,f),Kt(f,v)}for(let _ of u.iterable(u.vec(r.values()))){let s=_;t.remove(s),rn(s)}return t.userData.kids=i,t},Pt=function(t,e){let r=t;for(;;){if(u.truth_(r)){let i=u.get(r.userData.props,e);if(i!=null)return[i,r];r=r.parent;continue}break}},me=function(t){let e=t;for(;;){if(e==null)return!0;if(u.not(e.visible))return!1;e=e.parent;continue;break}};var at=Math.PI,Ft={color:"#b9bdc3",metalness:.8,roughness:.3},sr={color:"#d4af37",metalness:.8,roughness:.3},et=function(t,e){return[o.get(t,"x"),e,o.get(t,"z")]},It=function(t,e,r,i){let _=Bt(t,e),s=o.truth_(o.sequential_QMARK_(_))?o.truth_(o.vector_QMARK_(_))?_:o.seq_to_map_for_destructuring(_):_,q=o.get(s,"position"),a=o.get(s,"quaternion"),l=o.get(s,"length");return["cylinder",{position:q,quaternion:a,radius:r,height:l,segments:12,material:i}]},_r=function(t,e){let r=o.get(t,"labels");if(o.truth_(r)){let i=e==="off";return i||e==="bottom"===o.boolean$(o.get(t,"flipped"))}else return r},Wt=function(t,e,r,i,_,s){return["sprite",o.merge({text:r,size:i,position:_,"render-order":2,visible:_r(t,e)},s)]},pe=function(t,e){let r=o.map((function(s){return o.get(o.get(s,"hole"),"x")}),o.get(t,"pins")),i=o.map((function(s){return o.get(o.get(s,"hole"),"z")}),o.get(t,"pins")),_=(()=>{let s=o.get(o.get(t,"spec"),"extend");return o.truth_(s)?s:{}})();return{x0:o.apply(o.min,r)-e-(()=>{let s=o.get(_,"left");return o.truth_(s)?s:0})(),x1:o.apply(o.max,r)+e+(()=>{let s=o.get(_,"right");return o.truth_(s)?s:0})(),z0:o.apply(o.min,i)-e-(()=>{let s=o.get(_,"top");return o.truth_(s)?s:0})(),z1:o.apply(o.max,i)+e+(()=>{let s=o.get(_,"bottom");return o.truth_(s)?s:0})()}},ve=function(t){let e=o.filter((function(q){return o.get(q,"row")===0}),o.get(t,"pins")),r=o.filter((function(q){return o.get(q,"row")===1}),o.get(t,"pins")),i=o.map((function(q){return o.get(o.get(q,"hole"),"x")}),e),_=o.get(o.get(o.first(e),"hole"),"z"),s=o.get(o.get(o.first(r),"hole"),"z");return{x0:o.apply(o.min,i)-.6,x1:o.apply(o.max,i)+.6,z0:o.min(_,s)+.4,z1:o.max(_,s)-.4,dir:o.truth_(o.count(e)>1&&o.get(o.get(o.second(e),"hole"),"x")<o.get(o.get(o.first(e),"hole"),"x"))?-1:1,turned:_<s}},ir=function(t,e,r){let i=o.get(t,"spec"),_=(()=>{let Q=o.get(i,"lift");return o.truth_(Q)?Q:1})(),s=.22,q=(()=>{let Q=o.get(i,"color");return o.truth_(Q)?Q:"#1d5fa8"})(),a=pe(t,.6),l=o.truth_(o.sequential_QMARK_(a))?o.truth_(o.vector_QMARK_(a))?a:o.seq_to_map_for_destructuring(a):a,d=l,m=o.get(l,"x0"),v=o.get(l,"x1"),g=o.get(l,"z0"),p=o.get(l,"z1"),f=v-m,b=p-g,x=(m+v)/2,w=(g+p)/2,z=o.vals(o.group_by("row",o.get(t,"pins")));return["group",{},["box",{size:[f,s,b],position:[x,_+s/2,w],material:{color:q,emissive:e}}],["plane",{size:[f,b],rotation:[at/-2,0,0],position:[x,_+s+.004,w],material:{map:r,roughness:.85,emissive:e}}],o.lazy((function*(){for(let Q of o.iterable(z)){let N=Q;yield yield*(function*(){let T=o.map((function(S){return o.get(o.get(S,"hole"),"x")}),N),V=o.map((function(S){return o.get(o.get(S,"hole"),"z")}),N),B=o.apply(o.min,T),Y=o.apply(o.max,T),ot=o.apply(o.min,V),U=o.apply(o.max,V);return["box",{size:[.98*(1+(Y-B)),_,.98*(1+(U-ot))],position:[(B+Y)/2,_/2,(ot+U)/2],material:{color:"#1a1a1a",emissive:e}}]})()}return null})),o.lazy((function*(){for(let Q of o.iterable(yield*(function*(){let N=o.get(i,"marks");return o.truth_(N)?N:[]})())){let N=Q;yield yield*(function*(){let T=yield*(function*(){let B=o.get(N,"side");return o.truth_(B)?B:"left"})(),V=yield*(function*(){let B=T==="left";return B||T==="right"})();return["box",{size:o.truth_(V)?[1.8,.8,2.4]:[2.4,.8,1.8],position:[yield*(function*(){switch(T){case"left":return m+.5;case"right":return v-.5;default:return x}})(),_+s+.4,yield*(function*(){switch(T){case"top":return g+.5;case"bottom":return p-.5;default:return w}})()],material:o.assoc(Ft,"color","#c3c7cc","emissive",e)}]})()}return null}))]},cr=function(t,e,r){let i=ve(t),_=o.truth_(o.sequential_QMARK_(i))?o.truth_(o.vector_QMARK_(i))?i:o.seq_to_map_for_destructuring(i):i,s=o.get(_,"x0"),q=o.get(_,"x1"),a=o.get(_,"z0"),l=o.get(_,"z1"),d=o.get(_,"turned"),m=q-s,v=l-a,g=(s+q)/2,p=(a+l)/2,f=(()=>{let x=o.get(o.get(t,"spec"),"color");return o.truth_(x)?x:"#26272b"})(),b=o.assoc(Ft,"emissive",e);return["group",{},["box",{size:[m,1.25,v],position:[g,1.075,p],material:{color:f,roughness:.75,emissive:e}}],["plane",{size:[m,v],rotation:[at/-2,0,o.truth_(d)?at:0],position:[g,1.704,p],material:{map:r,roughness:.75,emissive:e}}],o.lazy((function*(){for(let x of o.iterable(o.get(t,"pins"))){let w=x;yield yield*(function*(){let z=o.get(w,"hole"),Q=o.get(z,"z")>p?1:-1;return["group",{},["box",{size:[.5,1.2,.12],position:[o.get(z,"x"),.6,o.get(z,"z")-Q*.05],material:b}],["box",{size:[.5,.12,Math.abs(o.get(z,"z")-p)-v/2+.1],position:[o.get(z,"x"),1.15,(o.get(z,"z")+p+Q*(v/2))/2],material:b}]]})()}return null}))]},ur=function(t,e,r){let i=o.get(t,"spec"),_=o.get(o.first(o.get(t,"pins")),"hole"),s=o.get(o.second(o.get(t,"pins")),"hole"),q=et(_,0),a=et(s,0),l=[(o.get(_,"x")+o.get(s,"x"))/2,0,(o.get(_,"z")+o.get(s,"z"))/2],d=Math.hypot(o.get(s,"x")-o.get(_,"x"),o.get(s,"z")-o.get(_,"z")),m=(o.get(s,"x")-o.get(_,"x"))/o.max(d,.001),v=(o.get(s,"z")-o.get(_,"z"))/o.max(d,.001),g=o.assoc(Ft,"emissive",e),p=(function(w){return[It(et(_,-.2),et(_,w),.07,g),It(et(s,-.2),et(s,w),.07,g)]}),f=(function(w,z){return[o.nth(l,0)+m*z,w,o.nth(l,2)+v*z]}),b=(function(w,z){return Bt(f(w,z/-2),f(w,z/2))});switch(o.get(t,"type")){case"resistor":let w=.75,z=o.min(2.6,o.max(1.2,d-.9)),Q=b(w,z),N=o.truth_(o.sequential_QMARK_(Q))?o.truth_(o.vector_QMARK_(Q))?Q:o.seq_to_map_for_destructuring(Q):Q,T=o.get(N,"position"),V=o.get(N,"quaternion"),B=o.count(r);return["group",{},p(w),It(et(_,w),et(s,w),.07,g),["capsule",{radius:.42,length:z-.84,position:T,quaternion:V,material:{color:(()=>{let H=o.get(i,"color");return o.truth_(H)?H:o.get(t,"type")==="diode"?"#2a2a2a":"#d9c49a"})(),roughness:.5,emissive:e}}],o.map_indexed((function(H,st){let _t=o.get(t,"type")==="diode"?.32:o.truth_(H===B-1&&B>1)?.3:-.3+H*(.36/o.max(1,B-2));return["cylinder",{radius:.445,height:.16,segments:16,quaternion:V,position:f(w,_t*z),material:{color:st,roughness:.5,emissive:e}}]}),r)];case"diode":let Y=.75,ot=o.min(2.6,o.max(1.2,d-.9)),U=b(Y,ot),S=o.truth_(o.sequential_QMARK_(U))?o.truth_(o.vector_QMARK_(U))?U:o.seq_to_map_for_destructuring(U):U,J=o.get(S,"position"),pt=o.get(S,"quaternion"),$t=o.count(r);return["group",{},p(Y),It(et(_,Y),et(s,Y),.07,g),["capsule",{radius:.42,length:ot-.84,position:J,quaternion:pt,material:{color:(()=>{let H=o.get(i,"color");return o.truth_(H)?H:o.get(t,"type")==="diode"?"#2a2a2a":"#d9c49a"})(),roughness:.5,emissive:e}}],o.map_indexed((function(H,st){let _t=o.get(t,"type")==="diode"?.32:o.truth_(H===$t-1&&$t>1)?.3:-.3+H*(.36/o.max(1,$t-2));return["cylinder",{radius:.445,height:.16,segments:16,quaternion:pt,position:f(Y,_t*ot),material:{color:st,roughness:.5,emissive:e}}]}),r)];case"led":let vt=1,Se=(()=>{let H=o.get(i,"color");return o.truth_(H)?H:"#e53935"})(),se={color:Se,roughness:.2,transparent:!0,opacity:.85,emissive:(()=>{let H=e;return o.truth_(H)?H:Se})(),"emissive-intensity":o.truth_(e)?1:o.truth_(o.get(i,"lit"))?.8:.15};return["group",{},p(vt),["cylinder",{radius:1.15,height:.25,position:[o.nth(l,0),vt+.12,o.nth(l,2)],material:se}],["cylinder",{radius:1,height:1.4,position:[o.nth(l,0),vt+.95,o.nth(l,2)],material:se}],["sphere",{radius:1,"theta-length":at/2,position:[o.nth(l,0),vt+1.65,o.nth(l,2)],material:se}]];case"capacitor":if(o.get(i,"kind")==="ceramic")return["group",{},p(.8),["cylinder",{radius:1,height:.4,position:[o.nth(l,0),1.4,o.nth(l,2)],quaternion:o.get(Bt([0,0,0],[-v,0,m]),"quaternion"),material:{color:(()=>{let H=o.get(i,"color");return o.truth_(H)?H:"#d9902f"})(),emissive:e}}]];{let H=(()=>{let _t=o.get(i,"size");return o.truth_(_t)?_t:o.max(1,d/2+.3)})(),st=H*2.2;return["group",{},p(.3),["cylinder",{radius:H,height:st,segments:28,position:[o.nth(l,0),.3+st/2,o.nth(l,2)],material:{color:(()=>{let _t=o.get(i,"color");return o.truth_(_t)?_t:"#24407a"})(),roughness:.4,emissive:e}}],["cylinder",{radius:H*1.01,height:st*.98,segments:28,"open-ended":!0,"theta-start":Math.atan2(m,v)-at/6,"theta-length":at/3,position:[o.nth(l,0),.3+st/2,o.nth(l,2)],material:{color:"#cfd3d8",side:"double",emissive:e}}],["cylinder",{radius:H*.98,height:.04,segments:28,position:[o.nth(l,0),.3+st+.02,o.nth(l,2)],material:o.assoc(Ft,"color","#c3c7cc","emissive",e)}]]}break;case"button":return["group",{},p(.3),["box",{size:[2.3,1.2,2.3],position:[o.nth(l,0),.6,o.nth(l,2)],material:{color:"#2b2b2b",emissive:e}}],["cylinder",{radius:.7,height:.6,position:[o.nth(l,0),1.5,o.nth(l,2)],material:{color:(()=>{let H=o.get(i,"color");return o.truth_(H)?H:"#5a5f66"})(),emissive:e}}]];default:return["group",{}]}},ar=function(t,e,r){return["group",{},o.map_indexed((function(i,_){let s=o.get(_,"hole");return["group",{},["box",{size:[.98,1,.98],position:[o.get(s,"x"),.5,o.get(s,"z")],material:{color:"#1a1a1a",emissive:e}}],["box",{size:[.25,2.4,.25],position:[o.get(s,"x"),2,o.get(s,"z")],material:o.assoc(sr,"emissive",e)}],o.truth_(o.seq(o.get(_,"name")))?Wt(r,"top",o.get(_,"name"),.6,[o.get(s,"x"),3.7+o.mod(i,2)*.8,o.get(s,"z")],{}):null]}),o.get(t,"pins"))]},lr=function(t,e){let r=o.get(t,"spec");if(o.truth_((()=>{let i=o.get(r,"label");if(o.truth_(i)){let _=o.seq(o.get(t,"pins"));return o.truth_(_)?o.not(o.contains_QMARK_(new Set(["dip","module"]),o.get(t,"type"))):_}else return i})())){let i=o.map((function(s){return o.get(o.get(s,"hole"),"x")}),o.get(t,"pins")),_=o.map((function(s){return o.get(o.get(s,"hole"),"z")}),o.get(t,"pins"));return Wt(e,"top",o.get(r,"label"),.75,[(o.apply(o.min,i)+o.apply(o.max,i))/2,(()=>{switch(o.get(t,"type")){case"led":return 4.2;case"capacitor":return 4.4;case"header":return 5;default:return 2.4}})(),(o.apply(o.min,_)+o.apply(o.max,_))/2],{})}},qr=function(t,e,r){let i=o.get(t,"key"),_=o.get(o.get(e,"glow"),i);return["group",{key:`part:${i??""}`,visible:o.not(o.contains_QMARK_(o.get(e,"hidden"),i)),"on-click":(function(s){return o.get(e,"on-pick")({kind:"part",key:i,hit:s})})},o.truth_(o.seq(o.get(t,"pins")))?(()=>{switch(o.get(t,"type")){case"module":return ir(t,_,o.get(o.get(r,"parts"),i));case"dip":if(o.truth_(o.some((function(q){return o.get(q,"row")===1}),o.get(t,"pins"))))return cr(t,_,o.get(o.get(r,"parts"),i));break;case"header":return ar(t,_,e);default:if(o.count(o.get(t,"pins"))===2)return ur(t,_,o.get(o.get(r,"bands-of"),i,[]))}})():null,lr(t,e)]},dr=function(t){let e=o.get(t,"board"),r=(o.get(e,"z-min")+o.get(e,"z-max"))/2,i=(function(a){let l=o.get(o.get(a,"to"),"side");return o.truth_(o.contains_QMARK_(new Set(["right","top","bottom","left"]),l))?l:o.get(o.get(o.get(a,"from"),"hole"),"z")<r?"top":"bottom"}),_=o.filter((function(a){return o.get(o.get(a,"to"),"off")}),o.get(t,"wires")),s=o.vals(o.group_by((function(a){return`${i(a)??""}|${o.get(o.get(a,"to"),"off")??""}`}),_)),q=o.map((function(a){let l=i(o.first(a)),d=o.get(o.get(o.first(a),"to"),"off"),m=(()=>{let v=l==="top";return v||l==="bottom"})();return{side:l,text:d,wires:a,at:o.reduce(o._PLUS_,o.map((function(v){return o.truth_(m)?o.get(o.get(o.get(v,"from"),"hole"),"x"):o.get(o.get(o.get(v,"from"),"hole"),"z")}),a))/o.count(a),size:o.truth_(m)?.3+.8*(.8+.45*o.count(d)):1.3}}),s);return o.into({},o.mapcat((function(a){let l=a,d=o.nth(l,0,null),m=o.nth(l,1,null),v=o.sort_by("at",m),g=o.reduce((function(f,b){let x=o.last(f),w=o.truth_(x)?o.max(o.get(b,"at"),o.get(x,"pos")+(o.get(x,"size")+o.get(b,"size"))/2):o.get(b,"at");return o.conj(f,o.assoc(b,"pos",w))}),[],v),p=o.reduce(o._PLUS_,o.map((function(f){return o.get(f,"at")-o.get(f,"pos")}),g))/o.count(g);return o.mapcat((function(f){let b=o.get(f,"pos")+p,x=(()=>{switch(d){case"top":return[b,.4,o.get(e,"z-min")-3.5];case"bottom":return[b,.4,o.get(e,"z-max")+3.5];default:return[d==="left"?o.get(e,"x-min")-3.5:o.get(e,"x-max")+3.5,.4,b]}})(),w=.5+.4*(.8+.45*o.count(o.get(f,"text"))),z=(()=>{switch(d){case"top":return[0,0,-1.2];case"bottom":return[0,0,1.2];case"left":return[-w,0,0];default:return[w,0,0]}})(),Q=o.count(o.get(f,"wires"));return o.map_indexed((function(N,T){let V=.55*(N-(Q-1)/2);return[o.get(T,"key"),{point:o.truth_((()=>{let B=d==="top";return B||d==="bottom"})())?[o.nth(x,0)+V,.4,o.nth(x,2)]:[o.nth(x,0),.4,o.nth(x,2)+V],owner:N===0?x:null,out:z,text:o.get(f,"text")}]}),o.get(f,"wires"))}),g)}),o.group_by("side",q)))},hr=function(t,e,r,i,_){let s=o.get(t,"board"),q=o.get(e,"key"),a=o.get(e,"spec"),l=o.get(a,"side")==="bottom",d=l?-o.get(s,"thick"):0,m=l?-1:1,v=(()=>{let T=o.get(a,"color");return o.truth_(T)?T:"#2e7d32"})(),g=o.get(o.get(_,"glow"),q),p=et(o.get(o.get(e,"from"),"hole"),d-m*.4),f=o.get(i,q),b=o.truth_(f)?(()=>{let T=o.get(f,"point");return[o.nth(T,0),l?d-.4:o.nth(T,1),o.nth(T,2)]})():et(o.get(o.get(e,"to"),"hole"),d-m*.4),x=Math.hypot(o.nth(b,0)-o.nth(p,0),o.nth(b,2)-o.nth(p,2)),w=o.min(5,1.2+x*.12)+o.mod(r,4)*.25,z=[o.nth(p,0),d+m*w*1.3,o.nth(p,2)],Q=[o.nth(b,0),d+m*w*(o.truth_(f)?.6:1.3),o.nth(b,2)],N=o.mapv((function(T){return(o.nth(p,T)+3*o.nth(z,T)+3*o.nth(Q,T)+o.nth(b,T))/8}),[0,1,2]);return["group",{key:`wire:${q??""}`,visible:o.not(o.contains_QMARK_(o.get(_,"hidden"),q)),"on-click":(function(T){return o.get(_,"on-pick")({kind:"wire",key:q,hit:T})})},["tube",{points:[p,z,Q,b],curve:"bezier",radius:.17,segments:o.max(24,Math.round(x*3)),material:{color:v,roughness:.45,emissive:g}}],o.truth_(f)?["box",{size:[.55,.55,.55],position:b,material:{color:"#1a1a1a",emissive:g}}]:null,(()=>{let T=(()=>{let V=f;return o.truth_(V)?o.get(f,"owner"):V})();if(o.truth_(T)){let V=T;return Wt(_,"off",o.get(f,"text"),.8,[o.nth(V,0)+o.nth(o.get(f,"out"),0),o.nth(b,1)+m*.4,o.nth(V,2)+o.nth(o.get(f,"out"),2)],{})}})(),o.truth_(o.get(e,"id"))?Wt(_,l?"bottom":"top",o.get(e,"id"),.62,[o.nth(N,0),o.nth(N,1)+m*.55,o.nth(N,2)],{fg:"#ffffff",bg:v}):null]},gr=function(t,e,r){let i=o.get(t,"board"),_=o.get(i,"x-max")-o.get(i,"x-min"),s=o.get(i,"z-max")-o.get(i,"z-min"),q=(o.get(i,"x-min")+o.get(i,"x-max"))/2,a=(o.get(i,"z-min")+o.get(i,"z-max"))/2,l=o.boolean$(o.get(e,"flipped")),d={transparent:l,opacity:o.truth_(l)?.88:1,"depth-write":o.not(l)};return["group",{key:"board","on-click":(function(m){return o.get(e,"on-pick")({kind:"board",hit:m})})},["box",{size:[_,o.get(i,"thick"),s],position:[q,o.get(i,"thick")/-2,a],material:o.merge({color:o.get(i,"kind")==="breadboard"?"#e2dccb":o.get(i,"color"),roughness:.8},d)}],["plane",{key:"top",size:[_,s],rotation:[at/-2,0,0],position:[q,.003,a],material:o.merge({map:o.get(r,"top"),roughness:.85},d)}],["plane",{key:"bottom",size:[_,s],rotation:[at/2,0,0],position:[q,-.003-o.get(i,"thick"),a],material:o.merge({map:o.get(r,"bottom"),roughness:.85},d)}]]},fr=function(t,e){let r=o.truth_(o.get(e,"flipped"))?-.02-o.get(o.get(t,"board"),"thick"):.02;return["group",{key:"rings"},o.lazy((function*(){for(let i of o.iterable(o.get(e,"rings"))){let _=i;yield["ring",{key:o.get(_,"key"),inner:.3,outer:.48,rotation:[at/-2,0,0],position:[o.get(_,"x"),r,o.get(_,"z")],"render-order":3,material:{kind:"basic",color:"#ffb300",side:"double",transparent:!0,opacity:.95,"polygon-offset":-2}}]}return null}))]},on=function(t,e,r){let i=o.get(t,"board"),_=dr(t);return["group",{key:"content",position:[-((o.get(i,"x-min")+o.get(i,"x-max"))/2),0,-((o.get(i,"z-min")+o.get(i,"z-max"))/2)]},gr(t,e,r),o.lazy((function*(){for(let s of o.iterable(o.get(t,"parts")))yield qr(s,e,r);return null})),o.map_indexed((function(s,q){return hr(t,q,s,_,e)}),o.get(t,"wires")),fr(t,e),["group",{key:"extras"},o.get(e,"extras")]]},sn=[["hemisphere-light",{key:"sky",intensity:2}],["directional-light",{key:"sun",intensity:1.6,position:[-20,60,40]}]];import*as G from"squint-cljs/core.js";var pr="http://www.w3.org/2000/svg",_n=function(t){return t!=null&&G.not(G.string_QMARK_(t))&&G.fn_QMARK_(t[Symbol.iterator])},cn=function(t){let e=G.vector_QMARK_(t);return G.truth_(e)?G.string_QMARK_(G.first(t)):e},un=function(t,e){if(t==null)return null;if(G.truth_((()=>{let r=G.string_QMARK_(t);return G.truth_(r)?r:G.number_QMARK_(t)})()))return document.createTextNode(`${t??""}`);if(G.truth_(cn(t))){let i=G.seq(t),_=G.first(i),s=G.next(i),q=_,a=s,l=G.truth_(G.map_QMARK_(G.first(a)))?G.first(a):null,d=G.truth_(l)?G.rest(a):a,m=(()=>{let g=e;return G.truth_(g)?g:q==="svg"})(),v=G.truth_(m)?document.createElementNS(pr,q):document.createElement(q);for(let g of G.iterable(Object.keys((()=>{let p=l;return G.truth_(p)?p:{}})()))){let p=g,f=l[p];if(p==="style")for(let b of G.iterable(Object.keys(f))){let x=b;v.style.setProperty(x,f[x])}else G.truth_(p.startsWith("on-"))?v.addEventListener(p.slice(3),f):G.truth_((()=>{let b=G.false_QMARK_(f);return G.truth_(b)?b:f==null})())||(G.truth_(G.true_QMARK_(f))?v.setAttribute(p,""):v.setAttribute(p,f))}for(let g of G.iterable(G.truth_(_n(d))?d:[])){let p=g;for(let f of G.iterable(G.truth_((()=>{let b=_n(p);return G.truth_(b)?G.not(cn(p)):b})())?p:[p])){let x=un(f,m);if(G.truth_(x)){let w=x;v.appendChild(w)}}}return v}else return null},Ut=function(t){return un(t,!1)},Xt=function(t,e){t.textContent="";for(let r of G.iterable(e)){let _=Ut(r);if(G.truth_(_)){let s=_;t.appendChild(s)}}return t};import*as ht from"squint-cljs/core.js";import"squint-cljs/core.js";var ln=`:host {
  --accent: var(--theme-accent-light, var(--accent-500));
  --accent-strong: var(--theme-accent-strong-light, var(--accent-600));
  --accent-subtle: color-mix(in oklch, var(--accent) 12%, transparent);
  --bg-0: var(--theme-bg-0-light, var(--gray-50));
  --bg-1: var(--theme-bg-1-light, var(--gray-0, #ffffff));
  --bg-2: var(--theme-bg-2-light, var(--gray-100));
  --blue: var(--blue-500);
  --border-0: 1px solid var(--theme-border-0-light, var(--gray-200));
  --border-1: 1px solid var(--theme-border-1-light, var(--gray-300));
  --border-2: 1px solid var(--theme-border-2-light, var(--gray-500));
  --border-subtle: 1px solid var(--gray-100);
  --control-thumb: #ffffff;
  --control-track: var(--gray-300);
  --danger: var(--theme-danger-light, var(--danger-500));
  --danger-strong: var(--danger-600);
  --fg-0: var(--theme-fg-0-light, var(--gray-950));
  --fg-1: var(--theme-fg-1-light, var(--gray-600));
  --fg-2: var(--theme-fg-2-light, var(--gray-400));
  --fg-on-accent: var(--theme-fg-on-accent-light, #ffffff);
  --fg-on-danger: #ffffff;
  --fg-on-success: #ffffff;
  --fg-on-warning: #ffffff;
  --focus-outline: 2px solid var(--accent);
  --orange: var(--orange-500);
  --pink: var(--pink-500);
  --radius-control: 9px;
  --radius-lg: 12px;
  --radius-md: 8px;
  --radius-panel: 14px;
  --radius-pill: 9999px;
  --radius-sm: 5px;
  --radius-surface: 11px;
  --radius-xs: 3px;
  --series-1: var(--accent-500);
  --series-2: var(--blue-500);
  --series-3: var(--teal-500);
  --series-4: var(--orange-500);
  --shadow-0: 0 1px 2px rgba(0,0,0,0.05);
  --shadow-1: 0 1px 3px rgba(0,0,0,0.1), 0 1px 2px rgba(0,0,0,0.06);
  --shadow-2: 0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06);
  --shadow-3: 0 10px 15px rgba(0,0,0,0.1), 0 4px 6px rgba(0,0,0,0.05);
  --shadow-floating: 0 12px 32px rgba(0,0,0,0.12), 0 2px 6px rgba(0,0,0,0.06);
  --success: var(--theme-success-light, var(--success-500));
  --success-strong: var(--success-600);
  --teal: var(--teal-500);
  --warning: var(--theme-warning-light, var(--warning-500));
  --control-lg: var(--size-11);
  --control-md: var(--size-9);
  --control-sm: var(--size-7);
  --duration-considered: 480ms;
  --duration-fast: 160ms;
  --duration-standard: 240ms;
  --ease-enter: cubic-bezier(0.16, 1, 0.3, 1);
  --ease-exit: cubic-bezier(0.4, 0, 1, 1);
  --ease-spring: cubic-bezier(0.34, 1.4, 0.64, 1);
  --ease-standard: cubic-bezier(0.22, 1, 0.36, 1);
  --font-body: ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  --font-display: var(--font-body);
  --font-mono: ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace;
  --leading-body: 1.5;
  --leading-display: 1.1;
  --tracking-display: -0.02em;
  --weight-medium: 500;
  --weight-regular: 400;
  --size-1: 0.25rem;
  --size-2: 0.5rem;
  --size-3: 0.75rem;
  --size-4: 1rem;
  --size-5: 1.25rem;
  --size-6: 1.5rem;
  --size-7: 1.75rem;
  --size-8: 2rem;
  --size-9: 2.25rem;
  --size-10: 2.5rem;
  --size-11: 2.75rem;
  --size-12: 3rem;
  --size-13: 3.25rem;
  --size-14: 3.5rem;
  --size-15: 3.75rem;
  --size-16: 4rem;
  --font-xs: 0.75rem;
  --font-sm: 0.813rem;
  --font-base: 0.875rem;
  --font-md: 1rem;
  --font-lg: 1.25rem;
  --font-xl: 1.75rem;
  --font-2xl: 2.25rem;
  --font-3xl: 3rem;
  --accent-50: oklch(0.965 0.0200 286.0);
  --accent-100: oklch(0.925 0.0400 286.0);
  --accent-200: oklch(0.860 0.0750 286.0);
  --accent-300: oklch(0.770 0.1250 286.0);
  --accent-400: oklch(0.690 0.1700 286.0);
  --accent-500: oklch(0.595 0.2300 286.0);
  --accent-600: oklch(0.505 0.2550 286.0);
  --accent-700: oklch(0.450 0.2450 286.0);
  --accent-800: oklch(0.395 0.2100 286.0);
  --accent-900: oklch(0.350 0.1750 286.0);
  --accent-950: oklch(0.260 0.1300 286.0);
  --blue-50: oklch(0.965 0.0180 245.0);
  --blue-100: oklch(0.925 0.0380 245.0);
  --blue-200: oklch(0.860 0.0700 245.0);
  --blue-300: oklch(0.770 0.1150 245.0);
  --blue-400: oklch(0.690 0.1550 245.0);
  --blue-500: oklch(0.595 0.2000 245.0);
  --blue-600: oklch(0.505 0.2150 245.0);
  --blue-700: oklch(0.450 0.2050 245.0);
  --blue-800: oklch(0.395 0.1750 245.0);
  --blue-900: oklch(0.350 0.1450 245.0);
  --blue-950: oklch(0.260 0.1050 245.0);
  --danger-50: oklch(0.970 0.0140 25.0);
  --danger-100: oklch(0.935 0.0320 25.0);
  --danger-200: oklch(0.860 0.0730 25.0);
  --danger-300: oklch(0.765 0.1270 25.0);
  --danger-400: oklch(0.675 0.1840 25.0);
  --danger-500: oklch(0.610 0.2260 25.0);
  --danger-600: oklch(0.560 0.2200 25.0);
  --danger-700: oklch(0.490 0.1840 25.0);
  --danger-800: oklch(0.425 0.1530 25.0);
  --danger-900: oklch(0.365 0.1240 25.0);
  --danger-950: oklch(0.255 0.0860 25.0);
  --gray-0: oklch(1.000 0.0000 285.0);
  --gray-50: oklch(0.975 0.0030 285.0);
  --gray-100: oklch(0.955 0.0050 285.0);
  --gray-200: oklch(0.915 0.0100 285.0);
  --gray-300: oklch(0.850 0.0120 285.0);
  --gray-400: oklch(0.690 0.0250 285.0);
  --gray-500: oklch(0.530 0.0350 285.0);
  --gray-600: oklch(0.425 0.0350 285.0);
  --gray-700: oklch(0.350 0.0350 285.0);
  --gray-800: oklch(0.245 0.0250 285.0);
  --gray-900: oklch(0.190 0.0160 285.0);
  --gray-950: oklch(0.145 0.0110 285.0);
  --orange-50: oklch(0.980 0.0140 55.0);
  --orange-100: oklch(0.950 0.0340 55.0);
  --orange-200: oklch(0.900 0.0680 55.0);
  --orange-300: oklch(0.830 0.1150 55.0);
  --orange-400: oklch(0.760 0.1600 55.0);
  --orange-500: oklch(0.700 0.1850 55.0);
  --orange-600: oklch(0.620 0.1750 55.0);
  --orange-700: oklch(0.540 0.1500 55.0);
  --orange-800: oklch(0.460 0.1250 55.0);
  --orange-900: oklch(0.395 0.1000 55.0);
  --orange-950: oklch(0.280 0.0700 55.0);
  --pink-50: oklch(0.970 0.0140 345.0);
  --pink-100: oklch(0.935 0.0320 345.0);
  --pink-200: oklch(0.860 0.0700 345.0);
  --pink-300: oklch(0.765 0.1200 345.0);
  --pink-400: oklch(0.675 0.1750 345.0);
  --pink-500: oklch(0.610 0.2150 345.0);
  --pink-600: oklch(0.560 0.2100 345.0);
  --pink-700: oklch(0.490 0.1780 345.0);
  --pink-800: oklch(0.425 0.1480 345.0);
  --pink-900: oklch(0.365 0.1200 345.0);
  --pink-950: oklch(0.255 0.0840 345.0);
  --success-50: oklch(0.980 0.0160 152.0);
  --success-100: oklch(0.960 0.0380 152.0);
  --success-200: oklch(0.930 0.0650 152.0);
  --success-300: oklch(0.885 0.1120 152.0);
  --success-400: oklch(0.815 0.1780 152.0);
  --success-500: oklch(0.705 0.1850 152.0);
  --success-600: oklch(0.595 0.1500 152.0);
  --success-700: oklch(0.510 0.1190 152.0);
  --success-800: oklch(0.425 0.0900 152.0);
  --success-900: oklch(0.370 0.0710 152.0);
  --success-950: oklch(0.270 0.0510 152.0);
  --teal-50: oklch(0.980 0.0140 195.0);
  --teal-100: oklch(0.960 0.0300 195.0);
  --teal-200: oklch(0.930 0.0550 195.0);
  --teal-300: oklch(0.885 0.0850 195.0);
  --teal-400: oklch(0.815 0.1150 195.0);
  --teal-500: oklch(0.705 0.1250 195.0);
  --teal-600: oklch(0.595 0.1100 195.0);
  --teal-700: oklch(0.510 0.0900 195.0);
  --teal-800: oklch(0.425 0.0700 195.0);
  --teal-900: oklch(0.370 0.0560 195.0);
  --teal-950: oklch(0.270 0.0400 195.0);
  --warning-50: oklch(0.985 0.0120 76.0);
  --warning-100: oklch(0.965 0.0320 76.0);
  --warning-200: oklch(0.935 0.0580 76.0);
  --warning-300: oklch(0.890 0.0950 76.0);
  --warning-400: oklch(0.845 0.1290 76.0);
  --warning-500: oklch(0.790 0.1590 76.0);
  --warning-600: oklch(0.725 0.1530 76.0);
  --warning-700: oklch(0.625 0.1300 76.0);
  --warning-800: oklch(0.540 0.1070 76.0);
  --warning-900: oklch(0.465 0.0880 76.0);
  --warning-950: oklch(0.355 0.0650 76.0);
}

:host([data-theme="dark"]) {
  --accent: var(--theme-accent-dark, var(--accent-400));
  --accent-strong: var(--theme-accent-strong-dark, var(--accent-300));
  --accent-subtle: color-mix(in oklch, var(--accent) 16%, transparent);
  --bg-0: var(--theme-bg-0-dark, var(--gray-950));
  --bg-1: var(--theme-bg-1-dark, var(--gray-900));
  --bg-2: var(--theme-bg-2-dark, var(--gray-800));
  --blue: var(--blue-400);
  --border-0: 1px solid var(--theme-border-0-dark, var(--gray-800));
  --border-1: 1px solid var(--theme-border-1-dark, var(--gray-700));
  --border-2: 1px solid var(--theme-border-2-dark, var(--gray-500));
  --border-subtle: 1px solid color-mix(in oklch, var(--gray-800) 60%, transparent);
  --control-thumb: #ffffff;
  --control-track: var(--gray-700);
  --danger: var(--theme-danger-dark, var(--danger-400));
  --danger-strong: var(--danger-300);
  --fg-0: var(--theme-fg-0-dark, var(--gray-50));
  --fg-1: var(--theme-fg-1-dark, var(--gray-300));
  --fg-2: var(--theme-fg-2-dark, var(--gray-500));
  --fg-on-accent: var(--theme-fg-on-accent-dark, #ffffff);
  --fg-on-danger: #ffffff;
  --fg-on-success: #ffffff;
  --fg-on-warning: #ffffff;
  --focus-outline: 2px solid var(--accent);
  --orange: var(--orange-400);
  --pink: var(--pink-400);
  --radius-control: 9px;
  --radius-lg: 12px;
  --radius-md: 8px;
  --radius-panel: 14px;
  --radius-pill: 9999px;
  --radius-sm: 5px;
  --radius-surface: 11px;
  --radius-xs: 3px;
  --series-1: var(--accent-400);
  --series-2: var(--blue-400);
  --series-3: var(--teal-400);
  --series-4: var(--orange-400);
  --shadow-0: 0 1px 2px rgba(0,0,0,0.2);
  --shadow-1: 0 1px 3px rgba(0,0,0,0.3), 0 1px 2px rgba(0,0,0,0.2);
  --shadow-2: 0 4px 6px rgba(0,0,0,0.3), 0 2px 4px rgba(0,0,0,0.2);
  --shadow-3: 0 10px 15px rgba(0,0,0,0.3), 0 4px 6px rgba(0,0,0,0.2);
  --shadow-floating: 0 22px 55px rgba(0,0,0,0.5), 0 2px 8px rgba(0,0,0,0.3);
  --success: var(--theme-success-dark, var(--success-400));
  --success-strong: var(--success-300);
  --teal: var(--teal-400);
  --warning: var(--theme-warning-dark, var(--warning-400));
}

@media (prefers-color-scheme: dark) {
  :host(:not([data-theme="light"])) {
    --accent: var(--theme-accent-dark, var(--accent-400));
    --accent-strong: var(--theme-accent-strong-dark, var(--accent-300));
    --accent-subtle: color-mix(in oklch, var(--accent) 16%, transparent);
    --bg-0: var(--theme-bg-0-dark, var(--gray-950));
    --bg-1: var(--theme-bg-1-dark, var(--gray-900));
    --bg-2: var(--theme-bg-2-dark, var(--gray-800));
    --blue: var(--blue-400);
    --border-0: 1px solid var(--theme-border-0-dark, var(--gray-800));
    --border-1: 1px solid var(--theme-border-1-dark, var(--gray-700));
    --border-2: 1px solid var(--theme-border-2-dark, var(--gray-500));
    --border-subtle: 1px solid color-mix(in oklch, var(--gray-800) 60%, transparent);
    --control-thumb: #ffffff;
    --control-track: var(--gray-700);
    --danger: var(--theme-danger-dark, var(--danger-400));
    --danger-strong: var(--danger-300);
    --fg-0: var(--theme-fg-0-dark, var(--gray-50));
    --fg-1: var(--theme-fg-1-dark, var(--gray-300));
    --fg-2: var(--theme-fg-2-dark, var(--gray-500));
    --fg-on-accent: var(--theme-fg-on-accent-dark, #ffffff);
    --fg-on-danger: #ffffff;
    --fg-on-success: #ffffff;
    --fg-on-warning: #ffffff;
    --focus-outline: 2px solid var(--accent);
    --orange: var(--orange-400);
    --pink: var(--pink-400);
    --radius-control: 9px;
    --radius-lg: 12px;
    --radius-md: 8px;
    --radius-panel: 14px;
    --radius-pill: 9999px;
    --radius-sm: 5px;
    --radius-surface: 11px;
    --radius-xs: 3px;
    --series-1: var(--accent-400);
    --series-2: var(--blue-400);
    --series-3: var(--teal-400);
    --series-4: var(--orange-400);
    --shadow-0: 0 1px 2px rgba(0,0,0,0.2);
    --shadow-1: 0 1px 3px rgba(0,0,0,0.3), 0 1px 2px rgba(0,0,0,0.2);
    --shadow-2: 0 4px 6px rgba(0,0,0,0.3), 0 2px 4px rgba(0,0,0,0.2);
    --shadow-3: 0 10px 15px rgba(0,0,0,0.3), 0 4px 6px rgba(0,0,0,0.2);
    --shadow-floating: 0 22px 55px rgba(0,0,0,0.5), 0 2px 8px rgba(0,0,0,0.3);
    --success: var(--theme-success-dark, var(--success-400));
    --success-strong: var(--success-300);
    --teal: var(--teal-400);
    --warning: var(--theme-warning-dark, var(--warning-400));
  }
}

*, *::before, *::after {
  box-sizing: border-box;
}

:host {
  font-family: var(--font-body);
  line-height: var(--leading-body);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

/* Body copy and control labels sit on sm (13px): the Fluid Functionalism
   body style. base (14px) is the subtitle, md (16px) the section title. */
:host {
  margin: 0;
  background: var(--bg-0);
  color: var(--fg-0);
  font-size: var(--font-sm);
}

/* Two weights for UI chrome: regular (400) and medium (500). Hierarchy
   comes from size, color and spacing, not heavier weights. b and strong
   are left alone: emphasis inside user content (markdown, messages)
   keeps the browser's bold. */
h1, h2, h3, h4, h5, h6, th {
  font-weight: var(--weight-medium);
}

/* The app type ramp (display / title / subtitle / body / caption).
   Headings carry no margin: layouts space them with gap (ui.page, card,
   section), and prose (ui.markdown) sets its own. An h1 is a page title
   at 28px, not a hero; apps should not restyle it. */
h1, h2, h3, h4, h5, h6 {
  margin: 0;
  text-wrap: balance;
}

h1 { font-size: var(--font-xl); }
h2 { font-size: var(--font-md); line-height: var(--leading-body); }
h3 { font-size: var(--font-base); }
h4, h5, h6 { font-size: var(--font-sm); }

p {
  text-wrap: pretty;
}

/* Display type: large headings get the display face, tight tracking and
   a short leading. */
h1, h2 {
  font-family: var(--font-display);
  letter-spacing: var(--tracking-display);
  line-height: var(--leading-display);
}

/* Numbers in columns and counters line up. */
td, th {
  font-variant-numeric: tabular-nums;
}

code, kbd, pre, samp {
  font-family: var(--font-mono);
}

/* One shared focus ring, keyboard only. Components never draw their own
   rings on pointer focus. */
:focus-visible {
  outline: var(--focus-outline);
  outline-offset: 2px;
  box-shadow: none;
}

:focus:not(:focus-visible) {
  outline: none;
  box-shadow: none;
}

[data-no-transitions] *,
[data-no-transitions] *::before,
[data-no-transitions] *::after {
  transition: none !important;
}

/* Reduced motion: keep end states and feedback, drop travel and loops. */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: var(--size-1);
  padding: var(--size-1) var(--size-3);
  font-size: var(--font-sm);
  font-weight: 500;
  line-height: var(--size-5);
  border-radius: var(--radius-pill);
  /* Emphasis without shouting: a faint accent tint behind accent text,
     the same treatment the status tones get. */
  background: var(--accent-subtle);
  color: var(--accent);
}

.badge-icon {
  width: 0.875em;
  height: 0.875em;
  flex-shrink: 0;
}

.badge-sm {
  padding: 0 var(--size-2);
  font-size: var(--font-xs);
  line-height: var(--size-4);
  border-radius: var(--radius-pill);
}

.badge-secondary {
  background: var(--bg-2);
  color: var(--fg-0);
}

.badge-outline {
  background: transparent;
  color: var(--fg-0);
  border: var(--border-0);
}

.badge-success {
  color: var(--success);
  background: color-mix(in srgb, var(--success) 12%, var(--bg-0));
}

.badge-warning {
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 12%, var(--bg-0));
}

.badge-danger {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 12%, var(--bg-0));
}


/* \u2500\u2500 Button \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
   Quiet by default: the primary action is neutral ink on the page color,
   secondary is a bordered surface, danger and success are outlined and
   colored only in their label. \`.btn-loud\` on primary / danger / success
   swaps in the filled, saturated version for the one action on a screen
   that has to shout (a checkout, a destructive confirm).

   Every button pins its height to a control token so a row of buttons,
   inputs and selects lines up. */

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--size-2);
  min-height: var(--control-md);
  padding: 0 var(--size-4);
  font-size: var(--font-sm);
  font-weight: 500;
  line-height: var(--leading-body);
  white-space: nowrap;
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  cursor: pointer;
  font-family: inherit;
  -webkit-user-select: none;
  user-select: none;
  transition:
    background-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    opacity var(--duration-fast) var(--ease-standard),
    transform var(--duration-fast) var(--ease-standard);
}

/* Press feedback: a small scale on every button, slightly deeper on
   icon-only buttons. Reduced motion drops it via the global rule. */
.btn:active:not(:disabled) {
  transform: scale(0.97);
}

/* \u2500\u2500 Primary \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.btn-primary {
  background: var(--fg-0);
  color: var(--bg-0);
  border-color: var(--fg-0);
}
@media (hover: hover){.btn-primary:hover:not(:disabled){
  opacity: 0.9;
}}
.btn-primary:active:not(:disabled) {
  opacity: 0.84;
}

.btn-primary.btn-loud {
  background: var(--accent);
  color: var(--fg-on-accent);
  border-color: transparent;
}
@media (hover: hover){.btn-primary.btn-loud:hover:not(:disabled){
  background: var(--accent-strong);
  opacity: 1;
}}

/* \u2500\u2500 Secondary \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.btn-secondary {
  background: var(--bg-1);
  color: var(--fg-0);
  border: var(--border-0);
}
@media (hover: hover){.btn-secondary:hover:not(:disabled){
  background: var(--bg-2);
}}

/* \u2500\u2500 Ghost \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.btn-ghost {
  background: transparent;
  color: var(--fg-1);
}
@media (hover: hover){.btn-ghost:hover:not(:disabled){
  background: var(--bg-2);
  color: var(--fg-0);
}}

/* Pressed state for segmented toggles (e.g. ui.grid layout-toggle). */
.btn.btn-toggled {
  background: var(--bg-2);
  color: var(--fg-0);
}

/* \u2500\u2500 Danger \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.btn-danger {
  background: var(--bg-1);
  color: var(--danger);
  border: var(--border-0);
}
@media (hover: hover){.btn-danger:hover:not(:disabled){
  background: var(--bg-2);
  border-color: var(--danger);
}}

.btn-danger.btn-loud {
  background: var(--danger);
  color: var(--fg-on-danger);
  border-color: transparent;
}
@media (hover: hover){.btn-danger.btn-loud:hover:not(:disabled){
  background: var(--danger-strong);
}}

/* \u2500\u2500 Success \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.btn-success {
  background: var(--bg-1);
  color: var(--success);
  border: var(--border-0);
}
@media (hover: hover){.btn-success:hover:not(:disabled){
  background: var(--bg-2);
  border-color: var(--success);
}}

.btn-success.btn-loud {
  background: var(--success);
  color: var(--fg-on-success);
  border-color: transparent;
}
@media (hover: hover){.btn-success.btn-loud:hover:not(:disabled){
  background: var(--success-strong);
}}

/* \u2500\u2500 Link \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.btn-link {
  min-height: 0;
  background: transparent;
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: var(--size-1);
  padding: 0;
  border: none;
}
@media (hover: hover){.btn-link:hover:not(:disabled){
  color: var(--accent-strong);
}}

/* Link buttons rendered as <a> need reset styles */
a.btn {
  text-decoration: none;
}
a.btn-link {
  text-decoration: underline;
}

/* \u2500\u2500 Sizes \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */
/* The radius steps with the size, by the same size-1 the height steps,
   so a small button isn't a pill and a large one isn't boxy. */

.btn-sm {
  min-height: var(--control-sm);
  padding: 0 var(--size-3);
  border-radius: calc(var(--radius-control) - var(--size-1));
}

.btn-lg {
  min-height: var(--control-lg);
  padding: 0 var(--size-5);
  font-size: var(--font-base);
  border-radius: calc(var(--radius-control) + var(--size-1));
}

/* \u2500\u2500 Icon-only button \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */
/* A square the height of its size; the icon sits in the middle. */

.btn-icon {
  padding: 0;
  width: var(--control-md);
  line-height: 1;
  aspect-ratio: 1;
}

.btn-icon:active:not(:disabled) {
  transform: scale(0.94);
}

.btn-icon.btn-sm {
  width: var(--control-sm);
}

.btn-icon.btn-lg {
  width: var(--control-lg);
}

.btn-icon-round {
  border-radius: var(--radius-pill);
}

/* \u2500\u2500 Loading \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

/* Spinner inside a loading button adapts to the button's text color
   so it stays visible on any variant (primary, success, ghost, \u2026). */
.btn-loading .spinner {
  border-color: color-mix(in srgb, currentColor 30%, transparent);
  border-top-color: currentColor;
}

/* \u2500\u2500 Disabled \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */
/* A filled button fades as a whole. Outlined buttons keep their shape
   and only mute their fill and label, so an unavailable action still
   reads as a button that is off, not as plain text. */

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-secondary:disabled,
.btn-danger:disabled,
.btn-success:disabled {
  opacity: 1;
  background: var(--bg-2);
  color: var(--fg-2);
  border: var(--border-0);
}

.btn-ghost:disabled {
  opacity: 1;
  color: var(--fg-2);
}


/* \u2500\u2500 Button group \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */
/* A cluster of related buttons as a segmented control. Two flavors:    */
/* ghost (container-less, active item filled) and boxed (bordered, like  */
/* tabs-boxed). Items are icon-only or text with an optional count.       */

.button-group {
  display: inline-flex;
  align-items: center;
  gap: var(--size-1);
}

.button-group-item {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: var(--size-9);
  padding: 0 var(--size-3);
  gap: var(--size-2);
  border: none;
  background: transparent;
  color: var(--fg-1);
  font-family: inherit;
  font-size: var(--font-sm);
  font-weight: 500;
  line-height: var(--size-5);
  border-radius: var(--radius-control);
  cursor: pointer;
  text-decoration: none;
  white-space: nowrap;
  transition: background-color var(--duration-fast) var(--ease-standard), color var(--duration-fast) var(--ease-standard), box-shadow var(--duration-fast) var(--ease-standard);
}

@media (hover: hover){a.button-group-item:hover{
  text-decoration: none;
}}

@media (hover: hover){.button-group-item:hover{
  background: var(--bg-2);
  color: var(--fg-0);
}}

.button-group-item.is-active {
  background: var(--bg-2);
  color: var(--fg-0);
}

/* Square icon-only item */
.button-group-item-icon {
  width: var(--size-9);
  height: var(--size-9);
  padding: 0;
}

/* Muted trailing count badge */
.button-group-count {
  color: var(--fg-2);
  font-weight: 400;
}

.button-group-item.is-active .button-group-count {
  color: var(--fg-1);
}

/* Divider between sub-clusters */
.button-group-separator {
  flex: none;
  width: 1px;
  height: var(--size-5);
  margin: 0 var(--size-1);
  background: var(--bg-2);
}

/* \u2500\u2500 Boxed flavor \u2014 bordered container (like tabs-boxed) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

/* Concentric: item radius = track radius - track padding */
.button-group-boxed {
  gap: var(--size-1);
  background: var(--bg-2);
  border-radius: var(--radius-control);
  padding: var(--size-1);
}

.button-group-boxed .button-group-item {
  height: var(--size-8);
  border-radius: calc(var(--radius-control) - var(--size-1));
}

.button-group-boxed .button-group-item-icon {
  width: var(--size-8);
  height: var(--size-8);
}

@media (hover: hover){.button-group-boxed .button-group-item:hover{
  background: color-mix(in oklch, var(--bg-1) 50%, var(--bg-2));
}}

.button-group-boxed .button-group-item.is-active {
  background: var(--bg-1);
  color: var(--fg-0);
  box-shadow: var(--shadow-0);
}


/* \u2500\u2500 Context Menu \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */
/* Floating menu triggered by right-click. Shared across all targets. */

/* Touch: long-press opens the menu (ui/js/gestures dispatches a synthetic
   contextmenu). Disable the iOS press callout / text selection / image
   drag-lift on the opt-in surface so the system UI doesn't fight the
   long-press. Descendants are targeted explicitly because
   -webkit-user-drag does NOT inherit \u2014 without it, long-pressing an <img>
   inside a trigger lifts a drag ghost in front of the menu. */
.clj-ui-touch .context-menu-trigger,
.clj-ui-touch [data-long-press],
.clj-ui-touch .context-menu-trigger *,
.clj-ui-touch [data-long-press] * {
  -webkit-touch-callout: none;
  -webkit-user-select: none;
  user-select: none;
  -webkit-user-drag: none;
}

/* Touch: press feedback \u2014 ui/js/gestures toggles .clj-ui-pressing on the
   pressed trigger while a long-press is pending; scale it down slightly
   (iOS-style). The scale is held while the context menu is open and
   animates back when the menu dismisses. */
.clj-ui-touch .context-menu-trigger,
.clj-ui-touch [data-long-press] {
  transition: transform var(--duration-standard) var(--ease-standard);
}

.clj-ui-touch .clj-ui-pressing {
  transform: scale(0.97);
}

/* Menu open: ui/js/context_menu puts .clj-ui-menu-open on <html> while a
   menu is showing and removes it on dismiss. No text selection anywhere
   meanwhile \u2014 a drag or shift-click next to the menu must not highlight
   the page (the runtime also swallows further right-clicks: they only
   close the menu). \`*\` + !important because user-select does not inherit
   and app CSS may set \`user-select: text\` on a descendant with higher
   specificity; like the reduced-motion block this is a transient global
   override, not a component style. */
.clj-ui-menu-open,
.clj-ui-menu-open * {
  -webkit-user-select: none !important;
  user-select: none !important;
}

.context-menu {
  position: fixed;
  z-index: 9999;
  min-width: 10rem;
  padding: 0;
  background: var(--bg-1);
  border: var(--border-0);
  border-radius: var(--radius-panel);
  box-shadow: var(--shadow-floating);
  overflow-x: hidden;
  overflow-y: auto;
  /* Never taller than the viewport \u2014 scroll instead of overflowing.
     dvh tracks mobile browser chrome; vh is the fallback. */
  max-height: calc(100vh - 16px);
  max-height: calc(100dvh - 16px);
  animation: context-menu-in var(--duration-fast) var(--ease-enter);
  list-style: none;
  margin: 0;
}

@keyframes context-menu-in {
  from { opacity: 0; transform: scale(0.96); }
  to   { opacity: 1; transform: scale(1); }
}

/* \u2500\u2500 Item \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.context-menu-item {
  display: flex;
  align-items: center;
  gap: var(--size-2);
  width: 100%;
  padding: var(--size-2) var(--size-3);
  font-size: var(--font-sm);
  line-height: 1.25;
  color: var(--fg-0);
  background: none;
  border: none;
  cursor: pointer;
  text-decoration: none;
  text-align: left;
  white-space: nowrap;
}

.context-menu-item:focus-visible{
  background: var(--bg-2);
  outline: none;
}
@media (hover: hover){.context-menu-item:hover{
  background: var(--bg-2);
  outline: none;
}}

.context-menu-item--danger {
  color: var(--danger-600);
}

.context-menu-item--danger:focus-visible{
  background: var(--danger-50);
  color: var(--danger-700);
}
@media (hover: hover){.context-menu-item--danger:hover{
  background: var(--danger-50);
  color: var(--danger-700);
}}

/* \u2500\u2500 Confirm: tap again (default) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */
/* ui/js/context_menu arms a :confirm item on the first activation: the row
   fills with the danger color and a bar drains over
   --context-menu-arm-duration (set inline by the runtime, which owns the
   timeout). The other rows step back meanwhile. */

.context-menu--armed .context-menu-item:not(.context-menu-item--armed) {
  opacity: 0.45;
  transition: opacity var(--duration-fast) var(--ease-standard);
}

.context-menu-item--armed {
  position: relative;
  overflow: hidden;
  font-weight: var(--weight-medium);
  animation: context-menu-arm var(--duration-standard) var(--ease-spring);
}

.context-menu-item--armed::after {
  content: "";
  position: absolute;
  inset: auto 0 0 0;
  height: 2px;
  background: currentColor;
  opacity: 0.5;
  transform-origin: left;
  animation: context-menu-arm-drain var(--context-menu-arm-duration, 3000ms) linear forwards; /* lint-allow: countdown, not motion */
}

@keyframes context-menu-arm {
  from { transform: scale(0.97); }
  to   { transform: scale(1); }
}

@keyframes context-menu-arm-drain {
  from { transform: scaleX(1); }
  to   { transform: scaleX(0); }
}

/* \u2500\u2500 Confirm: panel (:confirm-style :panel) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */
/* The menu drills into a confirm page: back header, message, a full-width
   action and Cancel. */

.context-menu.context-menu--panel {
  width: calc(var(--size-16) * 4);
  max-width: calc(100vw - 16px);
}

.context-menu-panel {
  animation: context-menu-push var(--duration-standard) var(--ease-enter);
}

@keyframes context-menu-push {
  from { opacity: 0; transform: translateX(var(--size-3)); }
  to   { opacity: 1; transform: translateX(0); }
}

.context-menu-panel-head {
  display: flex;
  align-items: center;
  gap: var(--size-1);
  padding: var(--size-1);
  border-bottom: var(--border-subtle);
  font-size: var(--font-sm);
  color: var(--fg-1);
}

.context-menu-panel-back {
  display: grid;
  place-items: center;
  width: var(--size-8);
  height: var(--size-8);
  padding: 0;
  border: none;
  border-radius: var(--radius-control);
  background: none;
  color: var(--fg-1);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-standard),
              color var(--duration-fast) var(--ease-standard);
}

.context-menu-panel-back:focus-visible{
  background: var(--bg-2);
  color: var(--fg-0);
}
@media (hover: hover){.context-menu-panel-back:hover{
  background: var(--bg-2);
  color: var(--fg-0);
}}

.context-menu-panel-body {
  padding: var(--size-3) var(--size-3) 0;
}

.context-menu-panel-title {
  margin: 0;
  font-size: var(--font-sm);
  font-weight: var(--weight-medium);
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-menu-panel-message {
  margin: var(--size-1) 0 0;
  font-size: var(--font-sm);
  line-height: var(--leading-body);
  color: var(--fg-1);
}

.context-menu-panel-actions {
  display: grid;
  gap: var(--size-1);
  padding: var(--size-3);
}

.context-menu-panel-actions .btn {
  width: 100%;
}

/* \u2500\u2500 Separator \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.context-menu-separator {
  height: 1px;
  margin: 0;
  background: var(--gray-200);
}

/* \u2500\u2500 Dark mode \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

:host([data-theme="dark"]) .context-menu-item--danger {
  color: var(--danger-400);
}

:host([data-theme="dark"]) .context-menu-item--danger:focus-visible{
  background: var(--danger-950);
  color: var(--danger-300);
}
@media (hover: hover){:host([data-theme="dark"]) .context-menu-item--danger:hover{
  background: var(--danger-950);
  color: var(--danger-300);
}}

:host([data-theme="dark"]) .context-menu-separator {
  background: var(--gray-800);
}

@media (prefers-color-scheme: dark) {
  :host(:not([data-theme="light"])) .context-menu-item--danger {
    color: var(--danger-400);
  }

  :host(:not([data-theme="light"])) .context-menu-item--danger:focus-visible{
    background: var(--danger-950);
    color: var(--danger-300);
  }
@media (hover: hover){:host(:not([data-theme="light"])) .context-menu-item--danger:hover{
    background: var(--danger-950);
    color: var(--danger-300);
  }}

  :host(:not([data-theme="light"])) .context-menu-separator {
    background: var(--gray-800);
  }
}

/* Armed row colors: after the dark-mode blocks so they beat the danger
   item's themed hover colors on equal specificity. */

.context-menu.context-menu--armed .context-menu-item--armed, .context-menu.context-menu--armed .context-menu-item--armed:focus-visible{
  background: var(--danger);
  color: var(--fg-on-danger);
}
@media (hover: hover){.context-menu.context-menu--armed .context-menu-item--armed:hover{
  background: var(--danger);
  color: var(--fg-on-danger);
}}


/* \u2500\u2500 Form field wrapper \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-field {
  display: flex;
  flex-direction: column;
  gap: calc(var(--size-1) + 2px);
  margin-bottom: var(--size-4);
}

.form-field:last-child {
  margin-bottom: 0;
}

.form-field--inline {
  flex-direction: row;
  align-items: center;
  gap: var(--size-2);
}

.form-field--error .form-input,
.form-field--error .form-textarea,
.form-field--error .form-select {
  border-color: var(--danger);
}

.form-field--error .form-input:focus-visible,
.form-field--error .form-textarea:focus-visible,
.form-field--error .form-select:focus-visible {
  border-color: var(--danger);
}

/* \u2500\u2500 Label \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-label {
  display: block;
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--fg-0);
}

/* \u2500\u2500 Inputs (text, email, password, date, etc.) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

/* Fields sit on the surface color (not the page color) so they read as
   controls inside a card instead of holes in it, and share the control
   height with buttons and selects so mixed rows line up. */
.form-input,
.form-textarea,
.form-select {
  width: 100%;
  min-height: var(--control-md);
  padding: var(--size-2) var(--size-3);
  font-size: var(--font-sm);
  font-family: inherit;
  line-height: var(--leading-body);
  background: var(--bg-1);
  color: var(--fg-0);
  border: var(--border-1);
  border-radius: var(--radius-control);
  transition: border-color var(--duration-fast) var(--ease-standard);
}

/* iOS Safari draws date and time inputs natively: a fixed intrinsic width
   that overflows a narrow grid column, a shorter box than the text
   fields beside it, and a centered value. Drop the native look so they
   take the field styles, may shrink, and align their value left. */
.form-input {
  min-width: 0;
}

.form-input[type="date"],
.form-input[type="time"],
.form-input[type="datetime-local"],
.form-input[type="month"],
.form-input[type="week"] {
  -webkit-appearance: none;
  appearance: none;
  text-align: left;
}

.form-input::-webkit-date-and-time-value {
  text-align: left;
}

.form-input::placeholder,
.form-textarea::placeholder {
  color: var(--fg-2);
}

/* Focus is the border turning accent plus the caret: no ring, no glow. */
.form-input:focus-visible,
.form-textarea:focus-visible,
.form-select:focus-visible {
  border-color: var(--accent);
  outline: none;
  position: relative;
  z-index: 1;
}

.form-input:disabled,
.form-textarea:disabled,
.form-select:disabled {
  background: var(--bg-2);
  color: var(--fg-2);
  cursor: not-allowed;
}

.form-input--error,
.form-textarea--error {
  border-color: var(--danger);
}

.form-input--error:focus-visible,
.form-textarea--error:focus-visible {
  border-color: var(--danger);
  box-shadow: inset 0 0 6px color-mix(in srgb, var(--danger) 35%, transparent);
}

/* \u2500\u2500 Input with icons \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-input-wrap {
  position: relative;
  width: 100%;
}

.form-input-icon {
  position: absolute;
  top: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  pointer-events: none;
  color: var(--fg-2);
  /* Focused inputs get z-index: 1 (to lift their accent border over joined
     siblings) \u2014 keep the icon above them or it vanishes on focus. */
  z-index: 2;
}

/* Focus: tint the icon with the accent to match the focused border. */
.form-input-wrap:focus-within .form-input-icon {
  color: var(--accent);
}

.form-input-icon--left {
  left: var(--size-3);
}

.form-input-icon--right {
  right: var(--size-3);
}

.form-input--icon-left {
  padding-left: var(--size-8);
}

.form-input--icon-right {
  padding-right: var(--size-8);
}

/* \u2500\u2500 Textarea \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-textarea {
  min-height: 5rem;
  resize: vertical;
}

/* \u2500\u2500 Auto-growing Textarea \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-textarea-auto {
  field-sizing: content;
  min-height: unset;
  resize: none;
  overflow-y: auto;
  line-height: 1.5;
}

/* \u2500\u2500 Select \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-select {
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%2371717a' stroke-width='2'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right var(--size-2) center;
  padding-right: var(--size-8);
  cursor: pointer;
}

/* \u2500\u2500 Checkbox & Radio \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-checkbox,
.form-radio {
  appearance: none;
  width: 1rem;
  height: 1rem;
  margin: 0;
  flex-shrink: 0;
  background: var(--bg-0);
  border: var(--border-1);
  cursor: pointer;
  transition:
    background-color var(--duration-fast) var(--ease-standard), border-color var(--duration-fast) var(--ease-standard);
  vertical-align: middle;
}

.form-checkbox {
  border-radius: var(--radius-sm);
}

.form-radio {
  border-radius: var(--radius-pill);
}

.form-checkbox:checked,
.form-radio:checked {
  background: var(--accent);
  border-color: var(--accent);
}

.form-checkbox:checked {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='white' stroke-width='4'%3E%3Cpolyline points='20 6 9 17 4 12'/%3E%3C/svg%3E");
  background-size: 100%;
  background-position: center;
  background-repeat: no-repeat;
}

.form-radio:checked {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Ccircle cx='8' cy='8' r='4' fill='white'/%3E%3C/svg%3E");
  background-size: 100%;
  background-position: center;
  background-repeat: no-repeat;
}

.form-checkbox:focus-visible,
.form-radio:focus-visible {
  outline-offset: 0;
}

.form-checkbox:disabled,
.form-radio:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* \u2500\u2500 File input \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-file {
  font-size: var(--font-sm);
  font-family: inherit;
  color: var(--fg-0);
  cursor: pointer;
}

.form-file::file-selector-button {
  padding: var(--size-2) var(--size-3);
  font-size: var(--font-sm);
  font-family: inherit;
  font-weight: 500;
  background: var(--bg-2);
  color: var(--fg-0);
  border: var(--border-1);
  border-radius: var(--radius-control);
  cursor: pointer;
  margin-right: var(--size-3);
  transition:
    background-color var(--duration-fast) var(--ease-standard), border-color var(--duration-fast) var(--ease-standard);
}

@media (hover: hover){.form-file::file-selector-button:hover{
  background: var(--bg-1);
  border-color: var(--accent);
}}

.form-file:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.form-file:disabled::file-selector-button {
  cursor: not-allowed;
}

/* \u2500\u2500 Range input \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-range {
  width: 100%;
  height: 6px;
  appearance: none;
  background: var(--bg-2);
  border-radius: var(--radius-pill);
  cursor: pointer;
}

.form-range::-webkit-slider-thumb {
  appearance: none;
  width: 1.25rem;
  height: 1.25rem;
  background: var(--accent);
  border-radius: var(--radius-pill);
  transition: transform var(--duration-fast) var(--ease-standard);
}

@media (hover: hover){.form-range::-webkit-slider-thumb:hover{
  transform: scale(1.1);
}}

.form-range::-moz-range-thumb {
  width: 1.25rem;
  height: 1.25rem;
  background: var(--accent);
  border: none;
  border-radius: var(--radius-pill);
}

.form-range:focus {
  outline: none;
}

.form-range:focus::-webkit-slider-thumb {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 20%, transparent);
}

.form-range:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* \u2500\u2500 Hint text \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-hint {
  font-size: var(--font-xs);
  color: var(--fg-2);
}

/* \u2500\u2500 Error icon with tooltip \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-field-control {
  position: relative;
}

.form-field-control > .form-input,
.form-field-control > .form-textarea,
.form-field-control > .form-select {
  padding-right: var(--size-8);
}

.tooltip.form-error-icon {
  position: absolute;
  right: var(--size-2);
  top: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  color: var(--danger);
  cursor: pointer;
  z-index: 1;
}

.tooltip.form-error-icon::after {
  background: color-mix(in srgb, var(--danger) 18%, var(--bg-1));
  color: var(--fg-0);
  border: 1px solid color-mix(in srgb, var(--danger) 25%, transparent);
  white-space: normal;
  min-width: 12rem;
  max-width: 20rem;
  line-height: 1.4;
  left: auto;
  right: 0;
  transform: translateY(4px);
}

.tooltip.form-error-icon::before {
  border-top-color: color-mix(in srgb, var(--danger) 18%, var(--bg-1));
  left: auto;
  right: var(--size-1);
  transform: translateY(4px);
}

/* Hover-reveal transforms \u2014 gated like the tooltip reveal itself
   (see ui/tooltip.css). */
@media (hover: hover) {
  .tooltip.form-error-icon:hover::after {
    transform: translateY(0);
  }

  .tooltip.form-error-icon:hover::before {
    transform: translateY(0);
  }
}

/* \u2500\u2500 Fieldset & Legend \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-fieldset {
  border: none;
  padding: 0;
  margin: 0 0 var(--size-4) 0;
  display: flex;
  flex-direction: column;
  gap: var(--size-2);
}

.form-fieldset:last-child {
  margin-bottom: 0;
}

.form-fieldset--inline {
  flex-direction: row;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--size-4);
}

.form-fieldset--inline label {
  display: inline-flex;
  align-items: center;
  gap: var(--size-2);
  font-size: var(--font-sm);
  color: var(--fg-0);
  cursor: pointer;
}

.form-legend {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--fg-0);
  margin-bottom: var(--size-3);
}

.form-fieldset--inline .form-legend {
  width: 100%;
  margin-bottom: var(--size-3);
}

/* \u2500\u2500 Input group \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.form-group {
  display: flex;
  align-items: stretch;
}

.form-group > .form-input,
.form-group > .form-select,
.form-group > .btn,
.form-group > .form-input-wrap .form-input {
  border-radius: 0;
}

.form-group > *:first-child,
.form-group > *:first-child .form-input,
.form-group > *:first-child .form-select {
  border-top-left-radius: var(--radius-control);
  border-bottom-left-radius: var(--radius-control);
}

.form-group > *:last-child,
.form-group > *:last-child .form-input,
.form-group > *:last-child .form-select {
  border-top-right-radius: var(--radius-control);
  border-bottom-right-radius: var(--radius-control);
}

.form-group > *:not(:first-child) {
  margin-left: -1px;
}

/* Joined submit/action button: give it a matching border so the whole group
   reads as one outlined unit, and turn it accent when the group's input is
   focused. The input's seam-side border sits under the button's overlapping
   (-1px) border, so no doubled line shows between them. */
.form-group > .btn {
  border: var(--border-1);
}

.form-group:focus-within > .btn {
  border-color: var(--accent);
}

/* Stretch the icon-input's inner <input> to the group height so its bottom
   border lines up flush with the joined button (the button's line-height
   makes it the tallest child; without this the input leaves a ~1px step). */
.form-group > .form-input-wrap {
  display: flex;
}

.form-group-addon {
  display: inline-flex;
  align-items: center;
  padding: var(--size-2) var(--size-3);
  font-size: var(--font-sm);
  background: var(--bg-2);
  border: var(--border-1);
  color: var(--fg-1);
  white-space: nowrap;
}


/* \u2500\u2500 Icon \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.icon {
  display: inline-block;
  width: var(--size-5);
  height: var(--size-5);
  flex-shrink: 0;
  vertical-align: middle;
}

.icon-sm {
  width: var(--size-4);
  height: var(--size-4);
}

.icon-lg {
  width: var(--size-6);
  height: var(--size-6);
}

.icon-xl {
  width: var(--size-8);
  height: var(--size-8);
}


/* \u2500\u2500 Select \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */
/* Custom framework-drawn select. The trigger mimics a form input; the
   floating listbox is created by the __uiSelect JS runtime. */

.select {
  position: relative;
  display: block;
}

/* \u2500\u2500 Trigger \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.select-trigger {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: var(--control-md);
  padding: var(--size-2) var(--size-8) var(--size-2) var(--size-3);
  font-size: var(--font-sm);
  font-family: inherit;
  line-height: var(--leading-body);
  text-align: left;
  background-color: var(--bg-1);
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%2371717a' stroke-width='2'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right var(--size-2) center;
  color: var(--fg-0);
  border: var(--border-1);
  border-radius: var(--radius-control);
  cursor: pointer;
}

.select-trigger:focus-visible {
  border-color: var(--accent);
  outline: none;
}

.select-trigger:disabled {
  background-color: var(--bg-2);
  color: var(--fg-2);
  cursor: not-allowed;
}

.select-value {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.select-value--placeholder {
  color: var(--fg-2);
}

/* \u2500\u2500 Menu (created by the JS runtime) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.select-menu {
  position: fixed;
  z-index: 9999;
  min-width: 10rem;
  max-height: 16rem;
  padding: var(--size-1);
  overflow-y: auto;
  background: var(--bg-1);
  border: var(--border-0);
  border-radius: var(--radius-panel);
  box-shadow: var(--shadow-floating);
  animation: select-menu-in var(--duration-fast) var(--ease-enter);
}

@keyframes select-menu-in {
  from { opacity: 0; transform: scale(0.98); }
  to   { opacity: 1; transform: scale(1); }
}

/* \u2500\u2500 Option \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

.select-option {
  display: flex;
  align-items: center;
  width: 100%;
  padding: var(--size-2) var(--size-3);
  font-size: var(--font-sm);
  font-family: inherit;
  line-height: 1.25;
  text-align: left;
  color: var(--fg-0);
  background: none;
  border: none;
  border-radius: var(--radius-control);
  cursor: pointer;
  white-space: nowrap;
}

.select-option:focus-visible{
  background: var(--bg-2);
  outline: none;
}
@media (hover: hover){.select-option:hover{
  background: var(--bg-2);
  outline: none;
}}

.select-option--selected {
  color: var(--accent);
  font-weight: 500;
}

/* \u2500\u2500 Dark mode \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */

:host([data-theme="dark"]) .select-trigger {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%23a1a1aa' stroke-width='2'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
}

@media (prefers-color-scheme: dark) {
  :host(:not([data-theme="light"])) .select-trigger {
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%23a1a1aa' stroke-width='2'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  }
}


.spinner {
  display: inline-block;
  width: 1.5rem;
  height: 1.5rem;
  border: 2px solid var(--bg-2);
  border-top-color: var(--accent);
  border-radius: var(--radius-pill);
  animation: spinner-spin 1s linear infinite; /* lint-allow */
}

.spinner-xs {
  width: var(--size-3);
  height: var(--size-3);
}

.spinner-sm {
  width: 1rem;
  height: 1rem;
}

.spinner-lg {
  width: 2rem;
  height: 2rem;
  border-width: 3px;
}

.spinner-overlay {
  position: relative;
}

.spinner-overlay > * {
  opacity: 0.3;
  pointer-events: none;
}

.spinner-overlay::before {
  content: "";
  position: absolute;
  inset: 0;
  margin: auto;
  z-index: 1;
  width: 1.5rem;
  height: 1.5rem;
  border: 2px solid var(--bg-2);
  border-top-color: var(--accent);
  border-radius: var(--radius-pill);
  animation: spinner-spin 1s linear infinite; /* lint-allow */
}

@keyframes spinner-spin {
  to {
    transform: rotate(360deg);
  }
}


.tooltip {
  position: relative;
  display: inline-block;
}

.tooltip::before,
.tooltip::after {
  position: absolute;
  left: 50%;
  opacity: 0;
  visibility: hidden;
  transition: opacity var(--duration-fast) var(--ease-standard), transform var(--duration-fast) var(--ease-standard), visibility var(--duration-fast) var(--ease-standard);
  pointer-events: none;
  z-index: 1000;
}

.tooltip::after {
  content: attr(data-tooltip);
  bottom: calc(100% + 10px);
  transform: translateX(-50%) translateY(4px);
  padding: var(--size-2) var(--size-3);
  font-size: var(--font-xs);
  line-height: 1;
  white-space: nowrap;
  background: var(--fg-0);
  color: var(--bg-0);
  border-radius: var(--radius-md);
}

.tooltip::before {
  content: "";
  bottom: calc(100% - 1px);
  transform: translateX(-50%) translateY(4px);
  border: 6px solid transparent;
  border-top-color: var(--fg-0);
}

/* Reveal on hover \u2014 only on devices that actually hover. A bare :hover
   reveal makes iOS treat the first tap as hover (suppressing that tap's
   click), so touch devices would need a double-tap. See AGENTS.md
   "Mobile & Touch Friendliness". */
@media (hover: hover) {
  .tooltip:hover::before,
  .tooltip:hover::after {
    opacity: 1;
    visibility: visible;
    transform: translateX(-50%) translateY(0);
  }
}


`;var br=function(t){let e=(()=>{let r=t.parentElement,i=r==null?null:r.closest("[data-theme]");return i==null?null:i.getAttribute("data-theme")})();if(ht.truth_(e))return e;switch(t.getAttribute("theme")){case"system":return null;case"light":return"light";case"dark":return"dark";default:let i=getComputedStyle(document.documentElement).colorScheme,_=i.includes("dark");return ht.truth_((()=>{let s=_;return ht.truth_(s)?i.includes("light"):s})())?null:ht.truth_(_)?"dark":"light"}},xr=function(t){let e=(function(){let r=br(t);if(ht.truth_(r)){let i=r;return ht._EQ_(i,t.getAttribute("data-theme"))?null:t.setAttribute("data-theme",i)}else return t.removeAttribute("data-theme")});return e(),new MutationObserver(e).observe(document.documentElement,{attributes:!0,attributeFilter:["data-theme"],subtree:!0})},qn=function(t,e,r){let i=t.attachShadow({mode:"open"});return i.append(Ut(["style",`${ln??""}
${e??""}`]),Ut(r)),xr(t),i};import*as y from"squint-cljs/core.js";import*as P from"three";import{OrbitControls as kr}from"three/addons/controls/OrbitControls.js";var bt=function(t){return y.truth_(y.get(t,"frame"))?null:y.assoc_BANG_(t,"frame",requestAnimationFrame((function(){return y.assoc_BANG_(t,"frame",null),y.get(t,"controls").update(),y.get(t,"renderer").render(y.get(t,"scene"),y.get(t,"camera"))})))},Yt=(()=>{let t=(function(i,_){return Yt(i,_,null)}),e=(function(i,_,s){let q=performance.now();return(function a(l){let d=i>0?y.min(1,(l-q)/i):1,m=d<.5?2*d*d:1-Math.pow(-2*d+2,2)/2;if(_(m),d<1)return requestAnimationFrame(a);if(y.truth_(s))return s()})(q)});return(function(...i){let _=this;switch(i.length){case 2:return t.call(_,i[0],i[1]);case 3:return e.call(_,i[0],i[1],i[2]);default:throw new Error(`Invalid arity: ${i.length??""}`)}})})(),dn=function(t,e,r){if(y.truth_(e>0&&r>0))return y.get(t,"renderer").setSize(e,r,!1),y.get(t,"camera").aspect=e/r,y.get(t,"camera").updateProjectionMatrix(),bt(t)},wr=function(t){let e=t.min,r=t.max;return y.lazy((function*(){for(let i of y.iterable([e.x,r.x])){let _=i;for(let s of y.iterable([e.y,r.y])){let q=s;for(let a of y.iterable([e.z,r.z])){let l=a;yield new P.Vector3(_,q,l)}}}return null}))},be=(()=>{let t=(function(i,_,s){return be(i,_,s,1.1)}),e=(function(i,_,s,q){if(y.truth_(_.isEmpty()))return null;{let a=y.get(i,"camera"),l=_.getCenter(new P.Vector3),d=new P.Vector3(y.nth(s,0),y.nth(s,1),y.nth(s,2)).normalize(),m=wr(_),v=(function(p){return a.position.copy(l).addScaledVector(d,p),a.lookAt(l),a.near=p/50,a.far=p*6,a.updateProjectionMatrix(),a.updateMatrixWorld(!0)}),g=(()=>{let p=1.5*_.getSize(new P.Vector3).length(),f=0;for(;;){if(f===3)return p;{v(p);let b=y.apply(y.max,y.map((function(z){let Q=z.clone().project(a);return y.max(Math.abs(Q.x),Math.abs(Q.y))}),m)),x=p*b*q,w=f+1;p=x,f=w;continue}break}})();return v(g),y.assoc_BANG_(i,"flight",(()=>{let p=y.get(i,"flight");return y.truth_(p)?p:0})()+1),y.get(i,"controls").target.copy(l),y.get(i,"controls").update(),bt(i)}});return(function(...i){let _=this;switch(i.length){case 3:return t.call(_,i[0],i[1],i[2]);case 4:return e.call(_,i[0],i[1],i[2],i[3]);default:throw new Error(`Invalid arity: ${i.length??""}`)}})})(),$r=function(t,e,r,i){let _=y.get(t,"controls"),s=y.get(t,"camera"),q=_.target.clone(),a=s.position.clone(),l=(()=>{let d=y.get(t,"flight");return y.truth_(d)?d:0})()+1;return y.assoc_BANG_(t,"flight",l),Yt(i,(function(d){if(l===y.get(t,"flight"))return _.target.lerpVectors(q,e,d),s.position.lerpVectors(a,r,d),_.update(),bt(t)}))},hn=function(t,e,r){if(y.truth_(e.isEmpty()))return null;{let i=y.get(t,"camera"),_=y.get(t,"controls"),s=e.getCenter(new P.Vector3),q=y.max(r,e.getSize(new P.Vector3).length()),a=new P.Vector3().subVectors(i.position,_.target).normalize(),l=1.25*(q/2/Math.tan(i.fov*Math.PI/360));return $r(t,s,s.clone().addScaledVector(a,l),400)}},zr=function(t,e){let i=y.get(t,"renderer").domElement.getBoundingClientRect(),_=new P.Vector2(2*((e.clientX-i.left)/i.width)-1,-2*((e.clientY-i.top)/i.height)+1),s=new P.Raycaster;return s.setFromCamera(_,y.get(t,"camera")),y.some((function(q){let a=q.object;if(y.truth_((()=>{let l=me(a);return y.truth_(l)?Pt(a,"on-click"):l})()))return{object:a,point:q.point,distance:q.distance}}),s.intersectObjects(y.get(t,"root").children,!0))},gn=function(t,e){let r=new P.WebGLRenderer({canvas:t,antialias:!0,alpha:!0}),i=new P.Scene,_=new P.PerspectiveCamera((()=>{let d=y.get(e,"fov");return y.truth_(d)?d:32})(),1,.5,2e3),s=new kr(_,t),q=new P.Group,a={renderer:r,scene:i,camera:_,controls:s,root:q,frame:null},l=y.atom(null);return r.setPixelRatio(y.min(devicePixelRatio,2)),s.enableDamping=!0,s.dampingFactor=(()=>{let d=y.get(e,"damping");return y.truth_(d)?d:.3})(),s.addEventListener("change",(function(){return bt(a)})),i.add(q),t.addEventListener("pointerdown",(function(d){return y.reset_BANG_(l,[d.clientX,d.clientY])})),t.addEventListener("pointerup",(function(d){let m=y.deref(l);if(y.truth_((()=>{let v=m;return y.truth_(v)?Math.hypot(d.clientX-y.nth(m,0),d.clientY-y.nth(m,1))<5:v})())){let v=zr(a,d);if(y.truth_(v)){let g=v;return y.first(Pt(y.get(g,"object"),"on-click"))(g)}else{let g=y.get(e,"on-miss");if(y.truth_(g))return g(d)}}})),a};import*as D from"squint-cljs/core.js";import*as mn from"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";import*as xt from"squint-cljs/core.js";import*as xe from"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";var F=function(t){return t};import*as K from"squint-cljs/core.js";import*as fn from"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";var Jt={"arrow-left":[["path",{d:"m12 19-7-7 7-7"}],["path",{d:"M19 12H5"}]],moon:[["path",{d:"M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401"}]],terminal:[["path",{d:"m4 17 6-6-6-6"}],["path",{d:"M12 19h8"}]],shuffle:[["path",{d:"m18 14 4 4-4 4"}],["path",{d:"m18 2 4 4-4 4"}],["path",{d:"M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-8.6a4 4 0 0 1 3.3-1.7H22"}],["path",{d:"M2 6h1.972a4 4 0 0 1 3.6 2.2"}],["path",{d:"M22 18h-6.041a4 4 0 0 1-3.3-1.8l-.359-.45"}]],"skip-back":[["path",{d:"M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z"}],["path",{d:"M3 20V4"}]],"file-text":[["path",{d:"M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"}],["path",{d:"M14 2v5a1 1 0 0 0 1 1h5"}],["path",{d:"M16 13H8"}],["path",{d:"M16 17H8"}],["path",{d:"M10 9H8"}]],"layout-dashboard":[["rect",{width:"7",height:"9",x:"3",y:"3",rx:"1"}],["rect",{width:"7",height:"5",x:"14",y:"3",rx:"1"}],["rect",{width:"7",height:"9",x:"14",y:"12",rx:"1"}],["rect",{width:"7",height:"5",x:"3",y:"16",rx:"1"}]],package:[["path",{d:"M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z"}],["path",{d:"M12 22V12"}],["polyline",{points:"3.29 7 12 12 20.71 7"}],["path",{d:"m7.5 4.27 9 5.15"}]],"arrow-right":[["path",{d:"M5 12h14"}],["path",{d:"m12 5 7 7-7 7"}]],"log-out":[["path",{d:"m16 17 5-5-5-5"}],["path",{d:"M21 12H9"}],["path",{d:"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"}]],home:[["path",{d:"M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"}],["path",{d:"M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"}]],copy:[["rect",{width:"14",height:"14",x:"8",y:"8",rx:"2",ry:"2"}],["path",{d:"M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"}]],heart:[["path",{d:"M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5"}]],"chevron-left":[["path",{d:"m15 18-6-6 6-6"}]],"book-open":[["path",{d:"M12 7v14"}],["path",{d:"M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z"}]],inbox:[["polyline",{points:"22 12 16 12 14 15 10 15 8 12 2 12"}],["path",{d:"M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"}]],"circle-x":[["circle",{cx:"12",cy:"12",r:"10"}],["path",{d:"m15 9-6 6"}],["path",{d:"m9 9 6 6"}]],zap:[["path",{d:"M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"}]],bookmark:[["path",{d:"M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z"}]],music:[["path",{d:"M9 18V5l12-2v13"}],["circle",{cx:"6",cy:"18",r:"3",fill:"currentColor"}],["circle",{cx:"18",cy:"16",r:"3",fill:"currentColor"}]],"chevron-down":[["path",{d:"m6 9 6 6 6-6"}]],"chevron-right":[["path",{d:"m9 18 6-6-6-6"}]],"arrow-up":[["path",{d:"m5 12 7-7 7 7"}],["path",{d:"M12 19V5"}]],refresh:[["path",{d:"M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"}],["path",{d:"M3 3v5h5"}],["path",{d:"M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"}],["path",{d:"M16 16h5v5"}]],film:[["rect",{width:"18",height:"18",x:"3",y:"3",rx:"2"}],["path",{d:"M7 3v18"}],["path",{d:"M3 7.5h4"}],["path",{d:"M3 12h18"}],["path",{d:"M3 16.5h4"}],["path",{d:"M17 3v18"}],["path",{d:"M17 7.5h4"}],["path",{d:"M17 16.5h4"}]],gauge:[["path",{d:"m12 14 4-4"}],["path",{d:"M3.34 19a10 10 0 1 1 17.32 0"}]],"alert-circle":[["circle",{cx:"12",cy:"12",r:"10"}],["line",{x1:"12",x2:"12",y1:"8",y2:"12"}],["line",{x1:"12",x2:"12.01",y1:"16",y2:"16"}]],"rotate-ccw":[["path",{d:"M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"}],["path",{d:"M3 3v5h5"}]],"alert-triangle":[["path",{d:"m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"}],["path",{d:"M12 9v4"}],["path",{d:"M12 17h.01"}]],"arrow-down":[["path",{d:"M12 5v14"}],["path",{d:"m19 12-7 7-7-7"}]],shield:[["path",{d:"M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"}]],folder:[["path",{d:"M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"}]],check:[["path",{d:"M20 6 9 17l-5-5"}]],grid:[["rect",{x:"3",y:"3",width:"18",height:"18",rx:"2"}],["path",{d:"M3 9h18"}],["path",{d:"M3 15h18"}],["path",{d:"M9 3v18"}],["path",{d:"M15 3v18"}]],"rotate-cw":[["path",{d:"M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"}],["path",{d:"M21 3v5h-5"}]],eye:[["path",{d:"M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"}],["circle",{cx:"12",cy:"12",r:"3"}]],mail:[["rect",{x:"2",y:"4",width:"20",height:"16",rx:"2"}],["path",{d:"m22 7-8.991 5.727a2 2 0 0 1-2.009 0L2 7"}]],"file-spreadsheet":[["path",{d:"M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"}],["path",{d:"M14 2v5a1 1 0 0 0 1 1h5"}],["path",{d:"M8 13h2"}],["path",{d:"M14 13h2"}],["path",{d:"M8 17h2"}],["path",{d:"M14 17h2"}]],edit:[["path",{d:"M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"}]],file:[["path",{d:"M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"}],["path",{d:"M14 2v5a1 1 0 0 0 1 1h5"}]],"external-link":[["path",{d:"M15 3h6v6"}],["path",{d:"M10 14 21 3"}],["path",{d:"M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]],settings:[["path",{d:"M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"}],["circle",{cx:"12",cy:"12",r:"3"}]],minus:[["path",{d:"M5 12h14"}]],"volume-2":[["path",{d:"M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"}],["path",{d:"M16 9a5 5 0 0 1 0 6"}],["path",{d:"M19.364 18.364a9 9 0 0 0 0-12.728"}]],star:[["path",{d:"M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"}]],"grip-vertical":[["circle",{cx:"9",cy:"12",r:"1"}],["circle",{cx:"9",cy:"5",r:"1"}],["circle",{cx:"9",cy:"19",r:"1"}],["circle",{cx:"15",cy:"12",r:"1"}],["circle",{cx:"15",cy:"5",r:"1"}],["circle",{cx:"15",cy:"19",r:"1"}]],"chevron-up":[["path",{d:"m18 15-6-6-6 6"}]],search:[["circle",{cx:"11",cy:"11",r:"8"}],["path",{d:"m21 21-4.34-4.34"}]],"volume-1":[["path",{d:"M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"}],["path",{d:"M16 9a5 5 0 0 1 0 6"}]],"eye-off":[["path",{d:"M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49"}],["path",{d:"M14.084 14.158a3 3 0 0 1-4.242-4.242"}],["path",{d:"M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143"}],["path",{d:"m2 2 20 20"}]],filter:[["polygon",{points:"22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"}]],"circle-check":[["circle",{cx:"12",cy:"12",r:"10"}],["path",{d:"m9 12 2 2 4-4"}]],maximize:[["path",{d:"M8 3H5a2 2 0 0 0-2 2v3"}],["path",{d:"M21 8V5a2 2 0 0 0-2-2h-3"}],["path",{d:"M3 16v3a2 2 0 0 0 2 2h3"}],["path",{d:"M16 21h3a2 2 0 0 0 2-2v-3"}]],calendar:[["rect",{width:"18",height:"18",x:"3",y:"4",rx:"2"}],["path",{d:"M16 2v4"}],["path",{d:"M8 2v4"}],["path",{d:"M3 10h18"}]],captions:[["rect",{width:"18",height:"14",x:"3",y:"5",rx:"2",ry:"2"}],["path",{d:"M7 15h4M15 15h2M7 11h2M13 11h4"}]],list:[["path",{d:"M3 5h.01"}],["path",{d:"M3 12h.01"}],["path",{d:"M3 19h.01"}],["path",{d:"M8 5h13"}],["path",{d:"M8 12h13"}],["path",{d:"M8 19h13"}]],link:[["path",{d:"M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"}],["path",{d:"M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"}]],mic:[["path",{d:"M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"}],["path",{d:"M19 10v2a7 7 0 0 1-14 0v-2"}],["path",{d:"M12 19v3"}]],monitor:[["rect",{width:"20",height:"14",x:"2",y:"3",rx:"2"}],["line",{x1:"8",x2:"16",y1:"21",y2:"21"}],["line",{x1:"12",x2:"12",y1:"17",y2:"21"}]],plus:[["path",{d:"M5 12h14"}],["path",{d:"M12 5v14"}]],"list-video":[["path",{d:"M12 12H3"}],["path",{d:"M16 6H3"}],["path",{d:"M12 18H3"}],["path",{d:"m16 12 5 3-5 3v-6Z"}]],bell:[["path",{d:"M10.268 21a2 2 0 0 0 3.464 0"}],["path",{d:"M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"}]],sun:[["circle",{cx:"12",cy:"12",r:"4"}],["path",{d:"M12 2v2"}],["path",{d:"M12 20v2"}],["path",{d:"m4.93 4.93 1.41 1.41"}],["path",{d:"m17.66 17.66 1.41 1.41"}],["path",{d:"M2 12h2"}],["path",{d:"M20 12h2"}],["path",{d:"m6.34 17.66-1.41 1.41"}],["path",{d:"m19.07 4.93-1.41 1.41"}]],minimize:[["path",{d:"M8 3v3a2 2 0 0 1-2 2H3"}],["path",{d:"M21 8h-3a2 2 0 0 1-2-2V3"}],["path",{d:"M3 16h3a2 2 0 0 1 2 2v3"}],["path",{d:"M16 21v-3a2 2 0 0 1 2-2h3"}]],info:[["circle",{cx:"12",cy:"12",r:"10"}],["path",{d:"M12 16v-4"}],["path",{d:"M12 8h.01"}]],code:[["path",{d:"m16 18 6-6-6-6"}],["path",{d:"m8 6-6 6 6 6"}]],camera:[["path",{d:"M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"}],["circle",{cx:"12",cy:"13",r:"3"}]],database:[["ellipse",{cx:"12",cy:"5",rx:"9",ry:"3"}],["path",{d:"M3 5V19A9 3 0 0 0 21 19V5"}],["path",{d:"M3 12A9 3 0 0 0 21 12"}]],repeat:[["path",{d:"m17 2 4 4-4 4"}],["path",{d:"M3 11v-1a4 4 0 0 1 4-4h14"}],["path",{d:"m7 22-4-4 4-4"}],["path",{d:"M21 13v1a4 4 0 0 1-4 4H3"}]],layers:[["path",{d:"M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z"}],["path",{d:"M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12"}],["path",{d:"M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17"}]],image:[["rect",{width:"18",height:"18",x:"3",y:"3",rx:"2",ry:"2"}],["circle",{cx:"9",cy:"9",r:"2"}],["path",{d:"m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"}]],"picture-in-picture-2":[["path",{d:"M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10c0 1.1.9 2 2 2h4"}],["rect",{width:"10",height:"7",x:"12",y:"13",rx:"2"}]],pause:[["rect",{x:"14",y:"4",width:"4",height:"16",rx:"1"}],["rect",{x:"6",y:"4",width:"4",height:"16",rx:"1"}]],clock:[["circle",{cx:"12",cy:"12",r:"10"}],["path",{d:"M12 6v6l4 2"}]],x:[["path",{d:"M18 6 6 18"}],["path",{d:"m6 6 12 12"}]],"log-in":[["path",{d:"m10 17 5-5-5-5"}],["path",{d:"M15 12H3"}],["path",{d:"M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"}]],"skip-forward":[["path",{d:"M21 4v16"}],["path",{d:"M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z"}]],"map-pin":[["path",{d:"M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"}],["circle",{cx:"12",cy:"10",r:"3"}]],play:[["path",{d:"M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z"}]],download:[["path",{d:"M12 15V3"}],["path",{d:"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}],["path",{d:"m7 10 5 5 5-5"}]],user:[["path",{d:"M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"}],["circle",{cx:"12",cy:"7",r:"4"}]],globe:[["circle",{cx:"12",cy:"12",r:"10"}],["path",{d:"M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"}],["path",{d:"M2 12h20"}]],"sliders-horizontal":[["path",{d:"M21 4h-7"}],["path",{d:"M10 4H3"}],["path",{d:"M21 12h-9"}],["path",{d:"M8 12H3"}],["path",{d:"M21 20h-5"}],["path",{d:"M12 20H3"}],["path",{d:"M14 2v4"}],["path",{d:"M8 10v4"}],["path",{d:"M16 18v4"}]],trash:[["path",{d:"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}],["path",{d:"M3 6h18"}],["path",{d:"M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]],menu:[["path",{d:"M4 5h16"}],["path",{d:"M4 12h16"}],["path",{d:"M4 19h16"}]],upload:[["path",{d:"M12 3v12"}],["path",{d:"m17 8-5-5-5 5"}],["path",{d:"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]],lock:[["rect",{width:"18",height:"11",x:"3",y:"11",rx:"2",ry:"2"}],["path",{d:"M7 11V7a5 5 0 0 1 10 0v4"}]],users:[["path",{d:"M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"}],["circle",{cx:"9",cy:"7",r:"4"}],["path",{d:"M16 3.128a4 4 0 0 1 0 7.744"}],["path",{d:"M22 21v-2a4 4 0 0 0-3-3.87"}]],"volume-x":[["path",{d:"M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"}],["path",{d:"M22 9l-6 6"}],["path",{d:"m16 9 6 6"}]]},Gr={play:[["path",{d:"M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z"}]],pause:[["rect",{x:"14",y:"4",width:"4",height:"16",rx:"1"}],["rect",{x:"6",y:"4",width:"4",height:"16",rx:"1"}]],"skip-back":[["path",{d:"M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z"}],["rect",{x:"2",y:"4",width:"2",height:"16",rx:"1"}]],"skip-forward":[["rect",{x:"20",y:"4",width:"2",height:"16",rx:"1"}],["path",{d:"M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z"}]],repeat:[["path",{d:"m17 2 4 4-4 4z"}],["path",{d:"M3 11v-1a4 4 0 0 1 4-4h14",fill:"none",stroke:"currentColor","stroke-width":"2"}],["path",{d:"m7 22-4-4 4-4z"}],["path",{d:"M21 13v1a4 4 0 0 1-4 4H3",fill:"none",stroke:"currentColor","stroke-width":"2"}]],"volume-2":[["path",{d:"M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"}],["path",{d:"M16 9a5 5 0 0 1 0 6",fill:"none",stroke:"currentColor","stroke-width":"2"}],["path",{d:"M19.364 18.364a9 9 0 0 0 0-12.728",fill:"none",stroke:"currentColor","stroke-width":"2"}]]},No=K.set(K.keys(Jt)),Ar="md",Rr=function(t){let e=t,r=K.truth_(K.sequential_QMARK_(e))?K.truth_(K.vector_QMARK_(e))?e:K.seq_to_map_for_destructuring(e):e,i=K.get(r,"size"),_=(()=>{let q=(()=>{let a=i;return a==null?null:F(a)})();return K.truth_(q)?q:Ar})(),s=["icon"];return _!=="md"?K.conj(s,`icon-${_??""}`):s},Qr=function(t){return fn.join(" ",Rr(t))},lt=function(t){let e=t,r=K.truth_(K.sequential_QMARK_(e))?K.truth_(K.vector_QMARK_(e))?e:K.seq_to_map_for_destructuring(e):e,i=r,_=K.get(r,"icon-name"),s=K.get(r,"size"),q=K.get(r,"class"),a=K.get(r,"attrs"),l=K.get(r,"filled"),m=F(_),v=K.truth_(l)?(()=>{let g=K.get(Gr,m);return K.truth_(g)?g:K.get(Jt,m)})():K.get(Jt,m);if(K.truth_(v)){let g=(()=>{let f=Qr({size:s});return K.truth_(q)?`${f??""} ${q??""}`:f})(),p=K.merge({stroke:K.truth_(l)?"none":"currentColor","aria-hidden":"true",fill:K.truth_(l)?"currentColor":"none","stroke-linejoin":"round",xmlns:"http://www.w3.org/2000/svg","stroke-linecap":"round","stroke-width":"2",class:g,viewBox:"0 0 24 24"},a);return K.into(["svg",p],v)}};var Er="primary",Br=function(t){let e=t,r=D.truth_(D.sequential_QMARK_(e))?D.truth_(D.vector_QMARK_(e))?e:D.seq_to_map_for_destructuring(e):e,i=D.get(r,"variant"),_=D.get(r,"size"),s=(()=>{let a=(()=>{let l=i;return l==null?null:F(l)})();return D.truth_(a)?a:Er})(),q=s==="primary"?["badge"]:["badge",`badge-${s??""}`];return(()=>{let a=_;return a==null?null:F(a)})()==="sm"?D.conj(q,"badge-sm"):q},Kr=function(t){return mn.join(" ",Br(t))},ye=(()=>{let t=(function(r,i){let _=r,s=D.truth_(D.sequential_QMARK_(_))?D.truth_(D.vector_QMARK_(_))?_:D.seq_to_map_for_destructuring(_):_,q=s,a=D.get(s,"variant"),l=D.get(s,"icon-name"),d=D.get(s,"size"),m=D.get(s,"class"),v=D.get(s,"attrs"),g=(()=>{let f=Kr({variant:a,size:d});return D.truth_(m)?`${f??""} ${m??""}`:f})(),p=D.merge({class:g},v);return D.into(["span",p],(()=>{let f=[],b=D.truth_(l)?D.conj(f,lt({"icon-name":l,size:"sm",class:"badge-icon"})):f;return D.into(b,i)})())}),e=(function(r,...i){let _=this;return t.call(_,r,i.length===0?null:i)});return e.squint$lang$variadic=t,e})();import*as k from"squint-cljs/core.js";import*as bn from"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";import*as C from"squint-cljs/core.js";import*as pn from"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";var Nr="md",jr=function(t){let e=t,r=C.truth_(C.sequential_QMARK_(e))?C.truth_(C.vector_QMARK_(e))?e:C.seq_to_map_for_destructuring(e):e,i=C.get(r,"size"),_=(()=>{let l=(()=>{let d=i;return d==null?null:F(d)})();return C.truth_(l)?l:Nr})(),s=["spinner"],q=_==="xs"?C.conj(s,"spinner-xs"):s,a=_==="sm"?C.conj(q,"spinner-sm"):q;return _==="lg"?C.conj(a,"spinner-lg"):a},Dr=function(t){return pn.join(" ",jr(t))},vn=function(t){let e=t,r=C.truth_(C.sequential_QMARK_(e))?C.truth_(C.vector_QMARK_(e))?e:C.seq_to_map_for_destructuring(e):e,i=r,_=C.get(r,"size"),s=C.get(r,"class"),q=C.get(r,"attrs"),a=(()=>{let d=Dr({size:_});return C.truth_(s)?`${d??""} ${s??""}`:d})();return["span",C.merge({class:a,role:"status","aria-label":"Loading"},q)]};var Hr="secondary",ke="md",Sr=function(t){let e=t,r=k.truth_(k.sequential_QMARK_(e))?k.truth_(k.vector_QMARK_(e))?e:k.seq_to_map_for_destructuring(e):e,i=k.get(r,"variant"),_=k.get(r,"size"),s=k.get(r,"loud"),q=k.get(r,"icon"),a=k.get(r,"round"),l=k.get(r,"loading"),d=(()=>{let x=(()=>{let w=i;return w==null?null:F(w)})();return k.truth_(x)?x:Hr})(),m=(()=>{let x=(()=>{let w=_;return w==null?null:F(w)})();return k.truth_(x)?x:ke})(),v=["btn",`btn-${d??""}`],g=k.truth_(s)?k.conj(v,"btn-loud"):v,p=m!=="md"?k.conj(g,`btn-${m??""}`):g,f=k.truth_(q)?k.conj(p,"btn-icon"):p,b=k.truth_((()=>{let x=q;return k.truth_(x)?a:x})())?k.conj(f,"btn-icon-round"):f;return k.truth_(l)?k.conj(b,"btn-loading"):b},Cr=function(t){return bn.join(" ",Sr(t))},kt=(()=>{let t=(function(r,i){let _=r,s=k.truth_(k.sequential_QMARK_(_))?k.truth_(k.vector_QMARK_(_))?_:k.seq_to_map_for_destructuring(_):_,q=s,a=k.get(s,"variant"),l=k.get(s,"size"),d=k.get(s,"loud"),m=k.get(s,"href"),v=k.get(s,"on-click"),g=k.get(s,"disabled"),p=k.get(s,"loading"),f=k.get(s,"icon-left"),b=k.get(s,"icon-right"),x=k.get(s,"icon"),w=k.get(s,"round"),z=k.get(s,"class"),Q=k.get(s,"attrs"),N=(()=>{switch(F((()=>{let J=l;return k.truth_(J)?J:ke})())){case"sm":return"sm";case"lg":return"md";default:return"sm"}})(),T=(()=>{let S=g;return k.truth_(S)?S:p})(),V=k.truth_(p)?vn({size:F((()=>{let S=l;return k.truth_(S)?S:ke})())==="sm"?"xs":"sm"}):null,B=k.truth_(m)?"a":"button",Y=(()=>{let S=Cr({variant:a,size:l,loud:d,icon:x,round:w,loading:p});return k.truth_(z)?`${S??""} ${z??""}`:S})(),ot=(()=>{let S=k.merge({class:Y},Q),J=k.truth_(m)?k.assoc(S,"href",m):S;return k.truth_(T)?k.assoc(J,"disabled",!0):J})(),U=(()=>{let S=ot;return k.truth_(v)?k.assoc(S,"on-click",v):S})();return k.truth_(x)?[B,U,k.truth_(p)?V:lt({"icon-name":x,size:N})]:k.into([B,U],(()=>{let S=[],J=k.truth_(p)?k.conj(S,V):S,pt=k.truth_((()=>{let vt=f;return k.truth_(vt)?k.not(p):vt})())?k.conj(J,lt({"icon-name":f,size:N})):J,$t=k.into(pt,i);return k.truth_(b)?k.conj($t,lt({"icon-name":b,size:N})):$t})())}),e=(function(r,...i){let _=this;return t.call(_,r,i.length===0?null:i)});return e.squint$lang$variadic=t,e})();import*as $ from"squint-cljs/core.js";import*as we from"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";var Lr=function(t){let e=t,r=$.truth_($.sequential_QMARK_(e))?$.truth_($.vector_QMARK_(e))?e:$.seq_to_map_for_destructuring(e):e,i=$.get(r,"variant"),_=(()=>{let q=(()=>{let a=i;return a==null?null:F(a)})();return $.truth_(q)?q:"ghost"})(),s=["button-group"];return _==="boxed"?$.conj(s,"button-group-boxed"):s},Vr=function(t){return we.join(" ",Lr(t))},Pr=function(t){let e=t,r=$.truth_($.sequential_QMARK_(e))?$.truth_($.vector_QMARK_(e))?e:$.seq_to_map_for_destructuring(e):e,i=$.get(r,"active"),_=$.get(r,"icon-only"),s=["button-group-item"],q=$.truth_(_)?$.conj(s,"button-group-item-icon"):s;return $.truth_(i)?$.conj(q,"is-active"):q},Ir=function(t){return we.join(" ",Pr(t))},yn=(()=>{let t=(function(r,i){let _=r,s=$.truth_($.sequential_QMARK_(_))?$.truth_($.vector_QMARK_(_))?_:$.seq_to_map_for_destructuring(_):_,q=s,a=$.get(s,"variant"),l=$.get(s,"class"),d=$.get(s,"attrs");return $.into(["div",$.merge({class:(()=>{let m=Vr({variant:a});return $.truth_(l)?`${m??""} ${l??""}`:m})(),role:"group"},d)],i)}),e=(function(r,...i){let _=this;return t.call(_,r,i.length===0?null:i)});return e.squint$lang$variadic=t,e})(),kn=(()=>{let t=(function(r,i){let _=r,s=$.truth_($.sequential_QMARK_(_))?$.truth_($.vector_QMARK_(_))?_:$.seq_to_map_for_destructuring(_):_,q=s,a=$.get(s,"active"),l=$.get(s,"icon"),d=$.get(s,"count"),m=$.get(s,"href"),v=$.get(s,"on-click"),g=$.get(s,"class"),p=$.get(s,"attrs"),f=l!=null&&$.empty_QMARK_(i),b=$.truth_(l)?lt({"icon-name":l,size:"sm"}):null,x=$.truth_(m)?"a":"button",w=(()=>{let Q=Ir({active:a,"icon-only":f});return $.truth_(g)?`${Q??""} ${g??""}`:Q})(),z=(()=>{let Q=$.merge({class:w},p),N=$.truth_(m)?$.assoc(Q,"href",m):Q;return $.truth_(v)?$.assoc(N,"on-click",v):N})();return $.into([x,z],(()=>{let Q=[],N=$.truth_(l)?$.conj(Q,b):Q,T=$.into(N,i);return d!=null?$.conj(T,["span",{class:"button-group-count"},d]):T})())}),e=(function(r,...i){let _=this;return t.call(_,r,i.length===0?null:i)});return e.squint$lang$variadic=t,e})();import*as O from"squint-cljs/core.js";import*as Xr from"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";import*as nt from"squint-cljs/core.js";import*as Z from"squint-cljs/core.js";import"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";import*as wt from"squint-cljs/core.js";import"squint-cljs/src/squint/string.js";import"squint-cljs/src/squint/string.js";var Zt=function(t){let e=t,r=O.truth_(O.sequential_QMARK_(e))?O.truth_(O.vector_QMARK_(e))?e:O.seq_to_map_for_destructuring(e):e,i=r,_=O.get(r,"label"),s=O.get(r,"checked"),q=O.get(r,"disabled"),a=O.get(r,"on-change"),l=O.get(r,"class"),d=O.get(r,"attrs"),m=(()=>{let v="form-field form-field--inline";return O.truth_(l)?`${v} ${l??""}`:v})();return["label",O.merge({class:m},d),["input",(()=>{let v={class:"form-checkbox",type:"checkbox"},g=O.truth_(s)?{...v,checked:!0}:v,p=O.truth_(q)?O.assoc(g,"disabled",!0):g;return O.truth_(a)?O.assoc(p,"on-change",a):p})()],O.truth_(_)?["span",_]:null]};var At="Drag to orbit, scroll to zoom. Click a hole, pin or wire to see what it connects to.",Jr=`
:host { display: block; position: relative; height: 440px; overflow: hidden; border-radius: var(--radius-panel);
        line-height: 1.35; color: var(--fg-0); background: var(--bb-background, transparent); }
.root { display: flex; height: 100%; }
.stage { position: relative; flex: 1; min-width: 0; }
canvas { display: block; width: 100%; height: 100%; cursor: grab; touch-action: none; }
.bar { position: absolute; left: 8px; top: 8px; right: 8px; display: flex; gap: 6px; align-items: center;
       pointer-events: none; }
.bar > * { pointer-events: auto; flex: none; }
.bar .title { flex: 0 1 auto; min-width: 0; margin-right: auto; padding: 4px 10px; font-weight: var(--weight-medium);
              border-radius: var(--radius-pill); background: var(--bg-1); box-shadow: var(--shadow-0);
              overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar .gap { margin-right: auto; }
.bar .btn-secondary, .steps .btn-secondary { background: var(--bg-1); }
.bar .btn.is-active { background: var(--bg-2); }
.info { position: absolute; left: 8px; bottom: 8px; max-width: calc(100% - 16px); padding: 5px 10px;
        border-radius: var(--radius-md); background: var(--bg-1); box-shadow: var(--shadow-1); pointer-events: none;
        white-space: pre-wrap; }
.info:empty { display: none; }
.problems { color: var(--danger); }
.has-steps .info { bottom: 62px; }
.steps { position: absolute; left: 8px; right: 8px; bottom: 8px; display: flex; gap: 8px; align-items: center;
         padding: 6px; border-radius: var(--radius-surface); border: var(--border-0);
         background: var(--bg-1); box-shadow: var(--shadow-1); }
.steps[hidden] { display: none; }
.stext { flex: 1; max-height: 3.6em; overflow: auto; }
.stext b { font-weight: var(--weight-medium); }
.side { width: 236px; flex: none; overflow: auto; padding: 8px; font-size: var(--font-xs, 12px);
        border-right: var(--border-0); background: var(--bg-0); }
.side[hidden] { display: none; }
.side ul { list-style: none; margin: 2px 0 10px; padding: 0; }
.side li { display: flex; align-items: center; gap: 6px; padding: 1px 4px; border-radius: var(--radius-sm); }
.side li.sel { background: var(--accent-subtle); }
.side .grp { font-weight: var(--weight-medium); margin: 4px 0 2px; }
.side .grp .badge { margin-left: 4px; }
.side .form-field { margin: 0; }
.side .sw { width: 14px; height: 7px; border-radius: 4px; flex: none; border: var(--border-1); }
.side .pick { flex: 1; min-width: 0; height: auto; min-height: 0; padding: 2px 4px; justify-content: flex-start;
              font-size: inherit; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.side .pick b { font-weight: var(--weight-medium); }
.side .sub { color: var(--fg-1); }
`,L=function(t){return t.bb},ft=function(t,e){return t.shadowRoot.querySelector(e)},te=function(t,e){return t.shadowRoot.querySelectorAll(e)},mt=function(t,e,r){let i=ft(t,".info");return i.textContent=(()=>{let _=e;return n.truth_(_)?_:""})(),i.classList.toggle("problems",n.boolean$(r))},rt=function(t){let e=L(t);if(n.truth_(n.get(e,"resolved")))return Kt(n.get(e,"pivot"),[on(n.get(e,"resolved"),{hidden:n.get(e,"hidden"),glow:n.get(e,"glow"),rings:n.get(e,"rings"),labels:n.get(e,"labels"),flipped:n.get(e,"flipped"),extras:n.get(e,"extras"),"on-pick":(function(r){return oo(t,r)})},n.get(e,"textures"))]),bt(n.get(e,"stage"))},ee=function(t){return n.first(n.get(L(t),"pivot").children)},Zr=function(t){return{top:de(t,!1),bottom:de(t,!0),parts:n.into({},n.keep((function(e){if(n.truth_(n.seq(n.get(e,"pins"))))switch(n.get(e,"type")){case"module":return[n.get(e,"key"),Je(e,pe(e,.6),(()=>{let i=n.get(n.get(e,"spec"),"color");return n.truth_(i)?i:"#1d5fa8"})())];case"dip":if(n.truth_(n.some((function(i){return n.get(i,"row")===1}),n.get(e,"pins")))){let i=ve(e);return[n.get(e,"key"),Ze(e,i,n.get(i,"dir"))]}break}})),n.get(t,"parts"))}},to=function(t){let e=L(t),r=n.get(e,"pivot"),i=r.rotation.x;r.rotation.x=0,r.updateMatrixWorld(!0);let _=new gt.Box3().setFromObject(ee(t));if(r.rotation.x=i,r.updateMatrixWorld(!0),n.truth_(n.get(e,"flipped"))){let s=_.min.clone(),q=_.max.clone();_.min.set(s.x,-q.y,-q.z),_.max.set(q.x,-s.y,-s.z)}return be(n.get(e,"stage"),_,n.truth_(n.contains_QMARK_(new Set(["top","bottom"]),n.get(e,"view")))?[0,1,1e-4]:[0,.82,.58],1.18)},Me=function(t,e){let r=L(t),i=ft(t,".stage");if(dn(n.get(r,"stage"),i.clientWidth,i.clientHeight),n.truth_((()=>{let _=e;return n.truth_(_)?n.get(r,"resolved"):_})()))return to(t)},eo=function(t){let e=t.onFullscreen;return n.truth_(e)?e():n.truth_(document.fullscreenElement)?document.exitFullscreen():t.requestFullscreen()},ne=function(t){let e=L(t),r=n.get(n.get(e,"layout"),"title");return Xt(ft(t,".bar"),[kt({variant:"secondary",size:"sm","icon-left":"layers",class:n.truth_(n.get(e,"side-open"))?"is-active":null,attrs:{title:"Parts and wires","aria-pressed":`${n.boolean$(n.get(e,"side-open"))??""}`},"on-click":(function(){return Mn(t,null)})},"Layers"),n.truth_(r)?["span",{class:"title",title:r},r]:["span",{class:"gap"}],n.into(yn({variant:"boxed"}),n.lazy((function*(){for(let i of n.iterable([["3d","3D"],["top","Top"],["bottom","Underside"]])){let _=i,s=n.nth(_,0,null),q=n.nth(_,1,null);yield kn({active:n._EQ_(s,n.get(e,"view")),"on-click":(function(){return Ge(t,s,!1)})},q)}return null}))),kt({variant:"secondary",size:"sm",icon:"maximize",attrs:{title:"Full screen","aria-label":"Full screen"},"on-click":(function(){return eo(t)})})])},no=function(t,e,r){let i=L(t),_=n.get(i,"pivot"),s=_.rotation.x,q=n.truth_(e)?Math.PI:0;return n.assoc_BANG_(i,"flipped",e),rt(t),Yt(n.truth_(r)?0:450,(function(a){return _.rotation.x=s+(q-s)*a,bt(n.get(i,"stage"))}))},Ge=function(t,e,r){let i=L(t);if(n.truth_(n.get(i,"resolved"))){let _=e==="bottom";return _===n.boolean$(n.get(i,"flipped"))||no(t,_,r),n.assoc_BANG_(i,"view",e),ne(t),Me(t,!0)}},Mt=function(t,e){let r=n.some((function(i){if(n._EQ_(e,n.get(i,"key")))return{kind:"part",obj:i}}),n.get(n.get(t,"resolved"),"parts"));return n.truth_(r)?r:n.some((function(i){if(n._EQ_(e,n.get(i,"key")))return{kind:"wire",obj:i}}),n.get(n.get(t,"resolved"),"wires"))},$n=function(t,e){let r=n.get(t,"resolved"),i=X(n.get(r,"board"),e);return n.truth_(i)?i:n.get(Dt(n.get(r,"parts"),e),"hole")},Gt=function(t,e){if(n.truth_(n.get(e,"off")))return n.get(e,"off");{let r=n.lazy((function*(){for(let i of n.iterable(n.get(n.get(t,"resolved"),"parts"))){let _=i;for(let s of n.iterable(n.get(_,"pins"))){let q=s;n._EQ_(n.get(n.get(q,"hole"),"key"),n.get(n.get(e,"hole"),"key"))&&(yield`${(yield*(function*(){let a=n.get(_,"id");return n.truth_(a)?a:n.get(_,"label")})())??""} ${n.get(q,"name")??""}`)}}return null}));return`${n.get(n.get(e,"hole"),"key")??""}${(n.truth_(n.seq(r))?` (${n.vec(r).join(", ")??""})`:null)??""}`}},Ae=function(t,e){let r=n.get(t,"resolved");return n.filterv((function(i){return n._EQ_(e,it(r,{hole:i}))}),Rt(n.get(r,"board")))},zn=function(t,e){let r=n.get(t,"resolved"),i=n.concat(n.lazy((function*(){for(let _ of n.iterable(n.get(r,"parts"))){let s=_;for(let q of n.iterable(n.get(s,"pins"))){let a=q;n._EQ_(e,it(r,{hole:n.get(a,"hole")}))&&(yield`${(yield*(function*(){let l=n.get(s,"id");return n.truth_(l)?l:n.get(s,"label")})())??""} ${(n.truth_(n.seq(n.get(a,"name")))?n.get(a,"name"):n.get(n.get(a,"hole"),"key"))??""}`)}}return null})),n.lazy((function*(){for(let _ of n.iterable(n.get(r,"wires"))){let s=_;n.truth_(yield*(function*(){let q=n.get(n.get(s,"to"),"off");return n.truth_(q)?n._EQ_(e,it(r,n.get(s,"from"))):q})())&&(yield n.get(n.get(s,"to"),"off"))}return null})));return`Connected (${n.count(Ae(t,e))??""} holes): ${(n.truth_(n.seq(i))?n.vec(n.distinct(i)).join(", "):"nothing else")??""}`},Re=function(t,e){let r=n.atom(null);for(let i of n.iterable(te(t,".side li"))){let _=i,s=n.some((function(q){return n._EQ_(q,_.dataset.id)}),e);_.classList.toggle("sel",n.boolean$(s)),n.truth_((()=>{let q=s;return n.truth_(q)?n.deref(r)==null:q})())&&n.reset_BANG_(r,_)}if(n.truth_(n.deref(r)))return n.deref(r).scrollIntoView({block:"nearest"})},$e=function(t,e,r){let i=L(t),_=n.atom([]),s=n.atom({}),q=n.atom([]);for(let d of n.iterable(dt(e))){let m=d,v=Mt(i,m);if(n.truth_(v)){let g=v,p=n.truth_(n.sequential_QMARK_(g))?n.truth_(n.vector_QMARK_(g))?g:n.seq_to_map_for_destructuring(g):g,f=n.get(p,"kind"),b=n.get(p,"obj");f==="wire"?(n.swap_BANG_(s,n.assoc,m,"#664400"),n.swap_BANG_(q,n.into,n.keep("hole",[n.get(b,"from"),n.get(b,"to")])),n.swap_BANG_(_,n.conj,`Wire ${m??""}${(n.truth_(n.seq(n.get(b,"label")))?` (${n.get(b,"label")??""})`:null)??""}: ${Gt(i,n.get(b,"from"))??""} \u2192 ${Gt(i,n.get(b,"to"))??""}`)):(n.swap_BANG_(s,n.assoc,m,"#3a3a3a"),n.swap_BANG_(_,n.conj,`${(()=>{let x=n.not_empty(n.get(b,"label"));return n.truth_(x)?x:m})()??""}${(()=>{let x=n.get(n.get(b,"spec"),"ohms");if(n.truth_(x))return` \xB7 ${x??""} \u03A9`})()??""}`))}else{let g=$n(i,m);if(n.truth_(g)){let p=g,f=it(n.get(i,"resolved"),{hole:p});n.swap_BANG_(q,n.into,Ae(i,f)),n.swap_BANG_(_,n.conj,`${m??""}: ${zn(i,f)??""}`)}}}n.assoc_BANG_(i,"glow",n.deref(s),"rings",n.deref(q)),Re(t,dt(e));let a=n.deref(_),l=n.count(a)>4?n.conj(n.vec(n.take(3,a)),`\u2026 and ${n.count(a)-3} more`):a;return mt(t,n.truth_(n.string_QMARK_(r))?r:n.truth_(n.seq(l))?n.vec(l).join(`
`):At,!1),rt(t)},Qe=function(t){return n.assoc_BANG_(L(t),"glow",{},"rings",[]),Re(t,[]),rt(t)},ro=function(t,e){let r=L(t),i=new gt.Box3;n.get(r,"pivot").updateMatrixWorld(!0);for(let _ of n.iterable(dt(e))){let s=_,q=(()=>{let a=Mt(r,s);if(n.truth_(a)){let l=a;return n.some((function(d){if(d.userData.props.key===`${n.get(l,"kind")??""}:${s??""}`)return d}),ee(t).children)}})();if(n.truth_(q))q.traverse((function(l){if(n.truth_((()=>{let d=l.geometry;return n.truth_(d)?n.not(l.isSprite):d})()))return i.expandByObject(l)}));else{let a=$n(r,s);if(n.truth_(a)){let l=a;i.expandByPoint(ee(t).localToWorld(new gt.Vector3(n.get(l,"x"),0,n.get(l,"z"))))}}}return hn(n.get(r,"stage"),i,16)},oo=function(t,e){let r=e,i=n.truth_(n.sequential_QMARK_(r))?n.truth_(n.vector_QMARK_(r))?r:n.seq_to_map_for_destructuring(r):r,_=n.get(i,"kind"),s=n.get(i,"key"),q=n.get(i,"hit"),a=L(t),l=n.get(a,"resolved"),d=(function(){let w=ee(t).worldToLocal(n.get(q,"point").clone());return n.first(n.sort_by((function(z){return Math.hypot(n.get(z,"x")-w.x,n.get(z,"z")-w.z)}),n.filter((function(z){return Math.hypot(n.get(z,"x")-w.x,n.get(z,"z")-w.z)<.8}),Rt(n.get(l,"board")))))}),m=(()=>{let w=_;switch(w){case"wire":let z=n.get(Mt(a,s),"obj");return[`Wire ${s??""}${(n.truth_(n.seq(n.get(z,"label")))?` (${n.get(z,"label")??""})`:null)??""}: ${Gt(a,n.get(z,"from"))??""} \u2192 ${Gt(a,n.get(z,"to"))??""}`,it(l,n.get(z,"from")),{},[s]];case"part":let Q=n.get(Mt(a,s),"obj"),N=d(),T=n.truth_(N)?n.some((function(B){if(n._EQ_(n.get(N,"key"),n.get(n.get(B,"hole"),"key")))return B}),n.get(Q,"pins")):null;return n.truth_(T)?[`${(()=>{let B=n.not_empty(n.get(Q,"label"));return n.truth_(B)?B:s})()??""} ${n.get(T,"name")??""} at ${n.get(N,"key")??""}`,it(l,{hole:n.get(T,"hole")}),{},[s]]:[`${(()=>{let B=n.not_empty(n.get(Q,"label"));return n.truth_(B)?B:s})()??""}${(()=>{let B=n.get(n.get(Q,"spec"),"ohms");if(n.truth_(B))return` \xB7 ${B??""} \u03A9`})()??""} \xB7 pins ${n.vec(n.map((function(B){return`${(n.truth_(n.seq(n.get(B,"name")))?`${n.get(B,"name")??""} `:null)??""}${n.get(n.get(B,"hole"),"key")??""}`}),n.get(Q,"pins"))).join(", ")??""}`,null,(()=>{let B={};return B[s]="#2a2a2a",B})(),[s]];case"board":let V=d();if(n.truth_(V)){let B=V,Y=n.lazy((function*(){for(let ot of n.iterable(n.get(l,"parts"))){let U=ot;for(let S of n.iterable(n.get(U,"pins"))){let J=S;n._EQ_(n.get(B,"key"),n.get(n.get(J,"hole"),"key"))&&(yield`${(yield*(function*(){let pt=n.not_empty(n.get(U,"label"));return n.truth_(pt)?pt:n.get(U,"key")})())??""} ${n.get(J,"name")??""}`)}}return null}));return[`Hole ${n.get(B,"key")??""}${(n.truth_(n.seq(Y))?` \xB7 ${n.vec(Y).join(", ")??""}`:null)??""}`,it(l,{hole:B}),{},[]]}else return["",null,{},[]];break;default:throw new Error(`No matching clause: ${w??""}`)}})(),v=n.nth(m,0,null),g=n.nth(m,1,null),p=n.nth(m,2,null),f=n.nth(m,3,null),b=n.truth_(g)?n.into({},n.lazy((function*(){for(let w of n.iterable(n.get(l,"wires"))){let z=w;n._EQ_(g,it(l,n.get(z,"from")))&&(yield[n.get(z,"key"),"#664400"])}return null}))):null;n.assoc_BANG_(a,"sticky",[],"glow",n.merge(p,b),"rings",n.truth_(g)?Ae(a,g):[]),Re(t,f);let x=n.truth_(g)?`${v??""}
${zn(a,g)??""}`:v;return mt(t,n.truth_(n.seq(x))?x:At,!1),rt(t),t.dispatchEvent(new CustomEvent("bb-select",{detail:{text:x},bubbles:!0,composed:!0}))},re=function(t){let e=L(t);for(let i of n.iterable(te(t,"li[data-id]"))){let _=i;_.querySelector("input").checked=n.not(n.contains_QMARK_(n.get(e,"hidden"),_.dataset.id))}for(let i of n.iterable(te(t,"[data-all]"))){let _=i,s=_.querySelector("input"),q=n.vec(te(t,`ul[data-kind=${_.dataset.all??""}] input`)),a=n.count(n.filter((function(l){return l.checked}),q));s.checked=n._EQ_(a,n.count(q)),s.indeterminate=a>0&&a<n.count(q)}let r=ft(t,"[data-labels] input");if(n.truth_(r)){let i=r;return i.checked=n.boolean$(n.get(e,"labels"))}},wn=function(t,e,r){let i=L(t);return n.assoc_BANG_(i,"hidden",n.reduce((function(_,s){return n.truth_(r)?n.disj(_,s):n.conj(_,s)}),n.get(i,"hidden"),e)),re(t),rt(t)},so=function(t){let e=L(t),r=n.get(e,"resolved"),i=(function(_,s,q){if(n.truth_(n.seq(q))){let a=n.mapv("key",q);return[Zt({class:"grp",attrs:{"data-all":s},label:["span",_,ye({variant:"secondary",size:"sm"},`${n.count(a)??""}`)],"on-change":(function(l){return wn(t,a,l.target.checked)})}),["ul",{"data-kind":s},n.lazy((function*(){for(let l of n.iterable(q)){let d=l;yield yield*(function*(){let m=n.get(d,"key"),v=s==="wire"?m:yield*(function*(){let p=n.not_empty(n.get(d,"label"));return n.truth_(p)?p:m})(),g=s==="wire"?` ${(n.truth_(n.seq(n.get(d,"label")))?n.get(d,"label"):`${Gt(e,n.get(d,"from"))??""} \u2192 ${Gt(e,n.get(d,"to"))??""}`)??""}`:n.truth_(yield*(function*(){let p=n.get(d,"id");if(n.truth_(p)){let f=n.seq(n.get(d,"label"));return n.truth_(f)?!n._EQ_(n.get(d,"label"),n.get(d,"id")):f}else return p})())?` ${n.get(d,"id")??""}`:"";return["li",{"data-id":m},Zt({attrs:{title:`Show ${v??""}`},"on-change":(function(p){return wn(t,[m],p.target.checked)})}),["span",{class:"sw",style:{background:yield*(function*(){let p=n.get(n.get(d,"spec"),"color");return n.truth_(p)?p:s==="wire"?"#2e7d32":"#9e9e9e"})()}}],kt({variant:"ghost",size:"sm",class:"pick",attrs:{title:`${v??""}${g??""}`},"on-click":(function(p){return Nt(t,{highlight:[m]})})},["b",v],["span",{class:"sub"},g])]})()}return null}))]]}});return Xt(ft(t,".side"),n.concat(i("Parts","part",n.get(r,"parts")),i("Wires","wire",n.get(r,"wires")),[Zt({class:"grp",attrs:{"data-labels":"1"},label:"Labels","on-change":(function(_){return n.assoc_BANG_(e,"labels",_.target.checked),rt(t)})})])),re(t)},Mn=function(t,e){let r=L(t),i=e==null?n.not(n.get(r,"side-open")):n.boolean$(e);return n.assoc_BANG_(r,"side-open",i),ft(t,".side").hidden=n.not(i),ne(t),Me(t,!1)},Gn=function(t){let e=L(t),r=n.get(e,"steps"),i=ft(t,".steps");if(i.hidden=n.empty_QMARK_(r),ft(t,".stage").classList.toggle("has-steps",n.boolean$(n.seq(r))),n.truth_(n.seq(r))){let _=n.get(e,"step"),s=_>=0?n.nth(r,_):null;return Xt(i,[kt({variant:"secondary",size:"sm",icon:"chevron-left",disabled:_<0,attrs:{"aria-label":"Previous step"},"on-click":(function(){return ze(t,n.get(L(t),"step")-1,!1)})}),["div",{class:"stext"},ye({variant:n.truth_(s)?"primary":"secondary",size:"sm"},n.truth_(s)?`${_+1}/${n.count(r)??""}`:`${n.count(r)??""} steps`)," ",["b",n.truth_(s)?(()=>{let q=n.get(s,"title");return n.truth_(q)?q:""})():"The whole board"],["span",n.truth_(s)?n.truth_(n.get(s,"text"))?` \xB7 ${n.get(s,"text")??""}`:"":" \xB7 next builds it up step by step"]],kt({variant:_<0?"primary":"secondary",size:"sm",icon:"chevron-right",disabled:_>=n.count(r)-1,attrs:{"aria-label":"Next step"},"on-click":(function(){return ze(t,n.get(L(t),"step")+1,!1)})})])}},ze=function(t,e,r){let i=L(t),_=n.get(i,"steps");if(n.truth_(n.seq(_))){let s=n.max(-1,n.min(n.count(_)-1,e)),q=n.set(n.mapcat((function(d){return dt(n.get(d,"add"))}),_)),a=n.set(n.mapcat((function(d){return dt(n.get(d,"add"))}),n.take(s+1,_))),l=s>=0?n.nth(_,s):null;return n.assoc_BANG_(i,"step",s,"hidden",s<0?new Set([]):n.set(n.remove((function(d){return n.contains_QMARK_(a,d)}),q)),"sticky",[]),n.truth_(l)?Nt(t,(()=>{let d=n.dissoc(l,"title","text","add","step"),m=n.truth_(n.get(l,"highlight")==null&&n.get(l,"add"))?n.assoc(d,"highlight",n.get(l,"add")):d;return n.get(l,"note")==null?n.assoc(m,"note",""):m})()):(Qe(t),re(t),mt(t,At,!1)),Gn(t),n.truth_(r)?null:t.dispatchEvent(new CustomEvent("bb-step",{detail:{step:s+1,of:n.count(_),title:n.truth_(l)?(()=>{let d=n.get(l,"title");return n.truth_(d)?d:""})():"the whole board",text:n.truth_(l)?(()=>{let d=n.get(l,"text");return n.truth_(d)?d:""})():""},bubbles:!0,composed:!0}))}},Nt=function(t,e){let r=L(t);if(n.not(n.get(r,"resolved")))return n.assoc_BANG_(r,"pending",n.merge(n.get(r,"pending"),e));{n.truth_(n.get(e,"clear"))&&(n.assoc_BANG_(r,"hidden",new Set([]),"sticky",[]),Qe(t),mt(t,At,!1)),n.truth_(n.get(e,"step")!=null&&n.seq(n.get(r,"steps")))&&ze(t,n.get(e,"step")-1,!0),n.truth_((()=>{let _=n.get(e,"view");return n.truth_(_)?!n._EQ_(n.get(e,"view"),n.get(r,"view")):_})())&&Ge(t,n.get(e,"view"),!1);let i=(function(_){return n.filterv((function(s){return Mt(r,s)}),dt(_))});if(n.truth_(n.get(e,"only"))){let _=i(n.get(e,"only")),s=n.set(n.map((function(a){return n.get(Mt(r,a),"kind")}),_)),q=n.concat(n.map((function(a){return[n.get(a,"key"),"part"]}),n.get(n.get(r,"resolved"),"parts")),n.map((function(a){return[n.get(a,"key"),"wire"]}),n.get(n.get(r,"resolved"),"wires")));n.assoc_BANG_(r,"hidden",n.set(n.lazy((function*(){for(let a of n.iterable(q)){let l=a,d=n.nth(l,0,null),m=n.nth(l,1,null);n.truth_(yield*(function*(){let v=n.contains_QMARK_(s,m);return n.truth_(v)?n.not(n.some((function(g){return n._EQ_(d,g)}),_)):v})())&&(yield d)}return null}))))}if(n.assoc_BANG_(r,"hidden",n.reduce(n.disj,n.get(r,"hidden"),i(n.get(e,"show")))),n.assoc_BANG_(r,"hidden",n.reduce(n.conj,n.get(r,"hidden"),i(n.get(e,"hide")))),n.get(e,"labels")!=null&&n.assoc_BANG_(r,"labels",n.boolean$(n.get(e,"labels"))),re(t),n.truth_(n.get(e,"highlight"))?(n.assoc_BANG_(r,"sticky",dt(n.get(e,"highlight")),"sticky-note",n.get(e,"note")),$e(t,n.get(e,"highlight"),n.get(e,"note"))):(n.get(e,"note")!=null&&mt(t,n.get(e,"note"),!1),rt(t)),n.truth_(n.get(e,"focus")))return ro(t,n.get(e,"focus"))}},Ee=function(t,e){let r=L(t);if(n.truth_(n.get(r,"resolved")))return n.truth_(e)?$e(t,e,null):n.truth_(n.seq(n.get(r,"sticky")))?$e(t,n.get(r,"sticky"),n.get(r,"sticky-note")):(Qe(t),mt(t,At,!1))},Be=function(t){let e=L(t),r=(()=>{let s=n.get(e,"layout");return n.truth_(s)?s:{}})(),i=Ie(r);n.assoc_BANG_(e,"resolved",i,"textures",n.assoc(Zr(i),"bands-of",n.into({},n.lazy((function*(){for(let s of n.iterable(n.get(i,"parts"))){let q=s;n.get(q,"type")==="resistor"&&(yield[n.get(q,"key"),tn(n.get(q,"spec"))])}return null})))),"hidden",new Set([]),"glow",{},"rings",[],"sticky",[],"step",-1,"steps",n.truth_(n.vector_QMARK_(n.get(r,"steps")))?n.get(r,"steps"):[],"extras",(()=>{let s=n.get(e,"extras");return n.truth_(s)?s:[]})()),t.resolved=i,ne(t),so(t),n.get(e,"side-open")==null&&Mn(t,n.truth_(t.hasAttribute("sidebar"))?t.getAttribute("sidebar")!=="closed":t.clientWidth>=720),Gn(t),mt(t,n.truth_(n.seq(n.get(i,"problems")))?n.vec(n.get(i,"problems")).join(`
`):At,n.seq(n.get(i,"problems"))),rt(t),Ge(t,(()=>{let s=n.get(r,"view");if(n.truth_(s))return s;{let q=t.getAttribute("view");return n.truth_(q)?q:"3d"}})(),!0);let _=n.get(e,"pending");if(n.truth_(_)){let s=_;return n.assoc_BANG_(e,"pending",null),Nt(t,s)}},_o=["div",{class:"root"},["aside",{class:"side",hidden:!0}],["div",{class:"stage"},["canvas"],["div",{class:"bar"}],["div",{class:"info"}],["div",{class:"steps",hidden:!0}]]],An=function(t){if(n.truth_(L(t)))return null;{let e=qn(t,Jr,_o),r=e.querySelector("canvas"),i=gn(r,{damping:.3}),_=new gt.Group,s={rings:[],labels:!0,stage:i,steps:[],sticky:[],hidden:new Set([]),pivot:_,step:-1,glow:{}};t.bb=s,t.stage=i,n.get(i,"root").add(_),n.get(i,"scene").add(Kt(new gt.Group,sn)),new ResizeObserver((function(){return Me(t,!1)})).observe(e.querySelector(".stage")),ne(t);let q=t.getAttribute("height");if(n.truth_(q)){let l=q;t.style.height=n.truth_(n.re_matches(/[0-9]+/,l))?`${l??""}px`:l}if(!n.truth_(s.layout)){let l=n.some((function(d){if(n.truth_(d.tagName==="SCRIPT"&&d.type==="application/json"))return d}),n.vec(t.children));if(n.truth_(l)){let d=l;try{s.layout=JSON.parse(d.textContent)}catch(m){mt(t,`Layout is not valid JSON: ${m.message??""}`,!0)}}}let a=t.pendingLayout;if(n.truth_(a)){let l=a;s.layout=l}if(n.truth_(s.layout))return Be(t)}};import*as M from"squint-cljs/core.js";import*as R from"squint-cljs/core.js";var oe=function(t){return parent.postMessage(t,"*")},Rn=function(t){let e=t,r=R.truth_(R.sequential_QMARK_(e))?R.truth_(R.vector_QMARK_(e))?e:R.seq_to_map_for_destructuring(e):e,i=R.get(r,"name"),_=R.get(r,"height"),s=R.get(r,"on-result"),q=R.get(r,"on-mode"),a={next:1,pending:new Map},l=(function(d){let m=(()=>{let v=R.get(R.get(d,"styles"),"variables");return R.truth_(v)?v:{}})();for(let v of R.iterable(Object.keys(m))){let g=v;document.documentElement.style.setProperty(g,m[g])}if(R.truth_(R.get(d,"theme"))&&document.documentElement.setAttribute("data-theme",R.get(d,"theme")),R.truth_((()=>{let v=q;return R.truth_(v)?R.get(d,"displayMode"):v})()))return q(R.get(d,"displayMode"))});return window.addEventListener("message",(function(d){let m=(()=>{let g=d.data;return R.truth_(g)?g:{}})(),v=(()=>{let g=R.get(m,"id");return R.truth_(g)?a.pending.get(R.get(m,"id")):g})();if(R.truth_(v))return a.pending.delete(R.get(m,"id")),R.truth_(R.get(m,"error"))?R.get(v,"ko")(R.get(m,"error")):R.get(v,"ok")(R.get(m,"result"));if(R.get(m,"method")==="ui/notifications/tool-result"){if(R.truth_(s))return s((()=>{let g=R.get(R.get(m,"params"),"structuredContent");return R.truth_(g)?g:{}})())}else return R.get(m,"method")==="ui/notifications/host-context-changed"?l((()=>{let g=R.get(m,"params");return R.truth_(g)?g:{}})()):null})),new Promise((function(d,m){let v=a.next;return a.next=v+1,a.pending.set(v,{ok:d,ko:m}),oe({jsonrpc:"2.0",id:v,method:"ui/initialize",params:{protocolVersion:"2026-01-26",clientInfo:{name:i,version:"1"},appCapabilities:{availableDisplayModes:["inline","fullscreen"]}}})})).then((function(d){return l((()=>{let m=R.get(d,"hostContext");return R.truth_(m)?m:{}})()),oe({jsonrpc:"2.0",method:"ui/notifications/size-changed",params:{width:document.body.clientWidth,height:(()=>{let m=_;return R.truth_(m)?m:480})()}}),oe({jsonrpc:"2.0",method:"ui/notifications/initialized",params:{}})})),a},Ke=function(t,e,r){return new Promise((function(i,_){let s=R.get(t,"next");return R.assoc_BANG_(t,"next",s+1),R.get(t,"pending").set(s,{ok:i,ko:_}),oe({jsonrpc:"2.0",id:s,method:e,params:r})}))},Te=function(t,e,r){return Ke(t,"tools/call",{name:e,arguments:r})},Ne=function(t,e){return Ke(t,"ui/update-model-context",{content:[{type:"text",text:e}]})};var Qn=function(t,e){return Ke(t,"ui/request-display-mode",{mode:e}).then((function(r){return R.get(r,"mode")}))};import*as j from"squint-cljs/core.js";var En=j.atom(null),uo=function(){let t=j.deref(En);return j.truth_(t)?t:j.reset_BANG_(En,import("squint-cljs"))},De=new Map,ao=j.atom(0),je=j.atom({ns:"user",state:{}}),lo=function(t){if(t===void 0)return"nil";if(j.truth_(j.string_QMARK_(t)))return JSON.stringify(t);if(j.truth_(j.fn_QMARK_(t)))return"#function";if(j.truth_(t instanceof Element))return`#<${t.tagName.toLowerCase()??""}>`;try{let r=JSON.stringify(t);return j.truth_(r)?r:`${t??""}`}catch{return`${t??""}`}},Bn=function(t){return uo().then((function(e){let r=`e${j.swap_BANG_(ao,j.inc)??""}`,i=e.compileStringEx(`(do ${t??""}
)`,{repl:!0,context:"return","elide-exports":!0,ns:j.get(j.deref(je),"ns")},j.get(j.deref(je),"state")),_=j.reset_BANG_(je,{ns:`${(()=>{let a=i.ns;return j.truth_(a)?a:"user"})()??""}`,state:{"ns-state":i["ns-state"]}}),s=i.javascript,q=document.createElement("script");return new Promise((function(a,l){return De.set(r,a),q.type="module",q.textContent=`const done = globalThis.__threeLive.get('${r}');
try { done({ value: await (async function () {
${s??""}
})() }); }
catch (e) { done({ error: String(e && e.message || e) }); }`,document.head.appendChild(q),setTimeout((function(){return a({error:"no result (did it compile to valid JS?)"})}),2e4)}))})).then((function(e){return j.truth_(j.get(e,"error"))?{error:j.get(e,"error")}:{value:lo(j.get(e,"value"))}})).catch((function(e){return{error:`${(()=>{let r=e.message;return j.truth_(r)?r:e})()??""}`}}))};globalThis.__threeLive={get:(function(t){return function(e){let r=De.get(t);if(j.truth_(r)){let i=r;return De.delete(t),i(e)}}})};var ho=function(t){return new Promise((function(e,r){return setTimeout(e,t)}))},go=function(t,e,r){let i=r,_=M.truth_(M.sequential_QMARK_(i))?M.truth_(M.vector_QMARK_(i))?i:M.seq_to_map_for_destructuring(i):i,s=M.get(_,"eval"),q=M.get(_,"eval-id");return globalThis.board=e,Bn(s).then((function(a){return Te(t,"breadboard_eval_result",M.truth_(M.get(a,"error"))?{id:q,error:M.get(a,"error")}:{id:q,value:M.get(a,"value")})}))},fo=async function(t,e){let r=0,i=0;for(;;){if(i<6){let _=await Te(t,"breadboard_wait",{board:await(async()=>{let s=M.get(e.layout,"id");return M.truth_(s)?s:""})(),after:r}).catch((function(s){return null}));if(M.truth_(await(async()=>{let s=_==null;return s||M.get(_,"isError")})())){await ho(2e3*(i+1));let s=r,q=i+1;r=s,i=q;continue}else{let s=await(async()=>{let l=M.get(_,"structuredContent");return M.truth_(l)?l:{}})();for(let l of M.iterable(await(async()=>{let d=M.get(s,"commands");return M.truth_(d)?d:[]})())){let d=l;M.truth_(M.get(d,"eval"))?go(t,e,d):e.command(d)}let q=await(async()=>{let l=M.get(s,"seq");return M.truth_(l)?l:r})(),a=0;r=q,i=a;continue}}break}},Kn=function(){let t=document.querySelector("breadboard-view"),e=M.atom("inline"),r=M.atom(!1),i=M.atom(null),_=Rn({name:"breadboard",height:480,"on-mode":(function(s){return M.reset_BANG_(e,s)}),"on-result":(function(s){return t.setLayout((()=>{let q=M.get(s,"layout");return M.truth_(q)?q:{}})()),M.truth_(M.deref(r))?null:(M.reset_BANG_(r,!0),fo(M.deref(i),t))})});return M.reset_BANG_(i,_),globalThis.board=t,t.onFullscreen=(function(){return Qn(_,M.deref(e)==="fullscreen"?"inline":"fullscreen").then((function(s){return M.reset_BANG_(e,s)}))}),t.addEventListener("bb-select",(function(s){if(M.truth_(M.seq(M.get(s.detail,"text"))))return Ne(_,`In the breadboard view the user selected: ${M.get(s.detail,"text")??""}`)})),t.addEventListener("bb-step",(function(s){let q=s.detail;return Ne(_,`The user is on tutorial step ${M.get(q,"step")??""} of ${M.get(q,"of")??""}: ${M.get(q,"title")??""}${(M.truth_(M.seq(M.get(q,"text")))?` (${M.get(q,"text")??""})`:null)??""}`)}))};var He=class extends HTMLElement{constructor(){super();let e=this,r=this}connectedCallback(){let e=this,r=this;return An(e)}setLayout(e){let r=this,i=this;r.layout=e;let _=L(r);return E.truth_(_)?(E.assoc_BANG_(_,"layout",e),Be(r)):r.pendingLayout=e}command(e){let r=this,i=this;return Nt(r,e)}add(e){let r=this,i=this,_=L(r);return E.assoc_BANG_(_,"extras",E.conj(E.vec(E.get(_,"extras")),e)),rt(r)}clearExtras(){let e=this,r=this;return E.assoc_BANG_(L(e),"extras",[]),rt(e)}},po=He,vo="[data-bb-highlight],[data-bb-focus],[data-bb-only],[data-bb-show],[data-bb-hide],[data-bb-view],[data-bb-step],[data-bb-note]",Oe=function(t){let e=t.closest("[data-breadboard]"),r=E.vec(document.querySelectorAll("breadboard-view")),i=E.truth_(e)?e.getAttribute("data-breadboard"):null,_=E.truth_(i)?E.some((function(s){if(E._EQ_(i,s.getAttribute("name")))return s}),r):null;return E.truth_(_)?_:E.first(r)},bo=function(t){return E.into({},E.keep((function(e){let r=t.getAttribute(`data-bb-${e??""}`);if(E.truth_(r)){let i=r;return[e,e==="step"?Number(i):i]}}),["highlight","focus","only","show","hide","view","step","note"]))},xo=function(){document.addEventListener("click",(function(e){let r=E.truth_(e.target.closest)?e.target.closest(vo):null,i=E.truth_(r)?Oe(r):null;if(E.truth_(i)){r.tagName==="A"&&e.preventDefault(),i.command(bo(r));let _=i.getBoundingClientRect();if(E.truth_((()=>{let s=_.bottom<0;return s||_.top>innerHeight})()))return i.scrollIntoView({behavior:"smooth",block:"center"})}}));let t=E.atom(null);return document.addEventListener("mouseover",(function(e){let r=E.truth_(e.target.closest)?e.target.closest("[data-bb-highlight]"):null;if(E._EQ_(r,E.deref(t)))return null;{let i=(()=>{let s=E.deref(t);return E.truth_(s)?Oe(E.deref(t)):s})();E.truth_(i)&&Ee(i,null),E.reset_BANG_(t,r);let _=(()=>{let s=r;return E.truth_(s)?Oe(r):s})();if(E.truth_(_))return Ee(_,r.getAttribute("data-bb-highlight"))}}))};E.truth_(customElements.get("breadboard-view"))||(customElements.define("breadboard-view",po),globalThis.threeCore=zt,xo(),E.truth_(globalThis.BREADBOARD_VIEW)&&Kn());export{po as BreadboardView};
