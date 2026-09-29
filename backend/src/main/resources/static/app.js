(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const state = {file:null,importId:null,input:[],output:null,variant:"v1",layers:{network:true,restriction:true,oks:true,result:true,labels:false},view:{cx:.5,cy:.5,scale:900},drag:null};
  const canvas=$("mapCanvas"),ctx=canvas.getContext("2d");
  const labels={source:"Источник",heat_network:"Тепловая сеть",heat_chamber:"Тепловая камера",oks_connection_point:"Точка подключения ОКС",restriction:"Ограничение",technical_node:"Технический узел",variant_summary:"Итоги варианта"};

  // ---- notifications ----
  const TOAST_TITLES={error:"Ошибка",warn:"Внимание",ok:"Готово"};
  function toast(message,error=false){
    const kind=error===true?"error":typeof error==="string"?error:"ok",box=$("toasts");if(!box)return;
    const el=document.createElement("div");el.className="toast "+kind;el.setAttribute("role",kind==="error"?"alert":"status");
    const body=document.createElement("div"),title=document.createElement("b"),text=document.createElement("p"),close=document.createElement("button");
    title.textContent=TOAST_TITLES[kind]||"Готово";text.textContent=message;close.type="button";close.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12"/></svg>';close.setAttribute("aria-label","Закрыть уведомление");
    body.append(title,text);el.append(body,close);box.append(el);
    while(box.children.length>4)box.firstChild.remove();
    const hide=()=>{if(!el.isConnected)return;el.classList.add("leaving");setTimeout(()=>el.remove(),180)};
    close.onclick=hide;setTimeout(hide,kind==="error"?14000:kind==="warn"?10000:5000)}

  // ---- dialog (instead of the browser's confirm) ----
  function ask({title,message,confirmText="Продолжить",cancelText="Отмена",danger=false}){
    return new Promise(resolve=>{
      const root=$("modalRoot"),previous=document.activeElement,back=document.createElement("div");back.className="modal-backdrop";
      back.innerHTML='<div class="modal" role="dialog" aria-modal="true" aria-labelledby="modalTitle"><div class="modal-head" id="modalTitle"></div><div class="modal-body"></div><div class="modal-actions"><button type="button" class="btn ghost" data-no></button><button type="button" class="btn primary" data-yes></button></div></div>';
      back.querySelector(".modal").classList.toggle("danger",danger);
      back.querySelector(".modal-head").textContent=title;back.querySelector(".modal-body").textContent=message;
      const yes=back.querySelector("[data-yes]"),no=back.querySelector("[data-no]");yes.textContent=confirmText;no.textContent=cancelText;
      const done=v=>{document.removeEventListener("keydown",key,true);back.remove();if(previous&&previous.focus)previous.focus();resolve(v)};
      const key=e=>{if(e.key==="Escape"){e.preventDefault();e.stopPropagation();done(false)}else if(e.key==="Tab"){const f=[no,yes],i=f.indexOf(document.activeElement);e.preventDefault();f[(i+(e.shiftKey?-1:1)+f.length)%f.length].focus()}};
      yes.onclick=()=>done(true);no.onclick=()=>done(false);back.addEventListener("mousedown",e=>{if(e.target===back)done(false)});
      document.addEventListener("keydown",key,true);root.append(back);yes.focus()})}

  // ---- the running task: what is happening, for how long, and that the server still answers ----
  const task={id:0,timer:null,ping:null,started:0,kind:"",lost:false};
  function taskClock(){
    const seconds=Math.floor((Date.now()-task.started)/1000);$("taskTime").textContent=`${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,"0")}`;
    if(task.kind==="calc"){
      const minutes=Math.floor(seconds/60);
      $("taskDetail").textContent=task.lost?"Нет ответа от сервера. Расчёт мог быть прерван — подождите или повторите запуск."
        :seconds<10?"Строим трассы и проверяем ограничения"
        :minutes<3?"Строим трассы и проверяем ограничения. На больших участках расчёт занимает несколько минут, страницу можно не закрывать."
        :`Расчёт идёт уже ${minutes} мин. Сервер отвечает — это не зависание, участок большой.`}}
  async function taskPing(){
    const started=performance.now();
    try{const r=await fetch("/actuator/health",{cache:"no-store"});if(!r.ok)throw 0;task.lost=false;$("taskPing").classList.remove("lost");$("taskPingText").textContent=`Сервер отвечает · ${Math.round(performance.now()-started)} мс`}
    catch(_){task.lost=true;$("taskPing").classList.add("lost");$("taskPingText").textContent="Нет связи с сервером, повторяем…"}}
  function taskStart(title,detail,opts={}){
    taskEnd();task.id++;task.started=Date.now();task.kind=opts.kind||"";task.lost=false;
    $("taskTitle").textContent=title;$("taskDetail").textContent=detail||"";$("taskTime").textContent="0:00";
    taskMode(task.id,{determinate:!!opts.determinate});
    $("taskPing").classList.toggle("hidden",!opts.ping);$("taskPing").classList.remove("lost");$("taskPingText").textContent="Проверяем связь с сервером…";
    $("taskCard").classList.remove("hidden");$("activity").classList.add("on");
    task.timer=setInterval(taskClock,500);taskClock();
    if(opts.ping){task.ping=setInterval(taskPing,4000);taskPing()}
    return task.id}
  // one step of a determinate bar (0..1) or an endless one; only for the task that owns the card
  function taskMode(id,{determinate,progress,title,detail}={}){
    if(id!==task.id)return;
    if(title!==undefined)$("taskTitle").textContent=title;if(detail!==undefined)$("taskDetail").textContent=detail;
    const bar=$("taskBar");if(determinate!==undefined)bar.classList.toggle("indeterminate",!determinate);
    if(progress!==undefined)bar.firstElementChild.style.width=`${Math.max(0,Math.min(1,progress))*100}%`;
    else if(determinate===false)bar.firstElementChild.style.width=""}
  function taskEnd(id){
    if(id!==undefined&&id!==task.id)return;
    clearInterval(task.timer);clearInterval(task.ping);task.timer=task.ping=null;task.kind="";
    $("taskCard").classList.add("hidden");$("activity").classList.remove("on")}
  async function api(url,options={}){const r=await fetch(url,options);if(!r.ok){let e;try{e=await r.json()}catch(_){e={message:`Ошибка HTTP ${r.status}`}}const err=new Error(e.message||e.code||`Ошибка HTTP ${r.status}`);err.code=e.code;throw err}return r}
  function busy(button,on,text){button.disabled=on;if(on){button.dataset.oldHtml=button.innerHTML;button.textContent=text}else if(button.dataset.oldHtml)button.innerHTML=button.dataset.oldHtml}
  // Everything that belongs to the previous area is dropped when another one is loaded: data, result, selection, view, notes.
  function resetSession(){
    state.session=(state.session||0)+1;               // a calculation still running for the old area must not draw its answer
    state.input=[];state.output=null;state.download=null;state.variant="v1";state.unproven="";state.calcMode=null;state.drag=null;
    unconnectedNotes=new Map();itemsKey="";items=[];itemsInput=null;itemsOutput=null;cache=null;cacheView=null;
    state.view={cx:.5,cy:.5,scale:900};
    for(const id of ["resultPanel","reopenResults","featureCard","mapToolbar","layerPanel","mapStatus"])$(id).classList.add("hidden");
    $("mapEmpty").classList.remove("hidden");$("mapStatus").textContent="";
    $("variantTabs").innerHTML="";$("featureProperties").innerHTML="";$("resultNotice").classList.add("hidden");
    $("scoreValue").textContent="—";$("costValue").textContent="—";$("penaltyValue").textContent="—";$("totalValue").textContent="—";$("scoreParts").innerHTML="";$("lengthValue").textContent="—";$("chamberValue").textContent="—";$("unconnectedValue").textContent="—";
    $("targetOrdinals").value="";
    $("settingsCard").classList.add("disabled");$("calculateButton").disabled=true;
    step("upload");
    try{const c=$("mapCanvas").getContext("2d");c.setTransform(1,0,0,1,0,0);c.clearRect(0,0,$("mapCanvas").width,$("mapCanvas").height)}catch(_){}
  }
  function step(name){const order=["upload","geometry","result"],at=order.indexOf(name);document.querySelectorAll(".steps li").forEach((li,i)=>{li.classList.toggle("active",i===at);li.classList.toggle("done",i<at)})}

  const drop=$("dropzone"),fileInput=$("fileInput"),upload=$("uploadButton");
  // At most one upload is ever in flight: picking another file, or clicking Upload again, cancels whatever the
  // previous click started, so an old request finishing late can never overwrite the import a newer click began.
  let activeUpload=null;
  function cancelActiveUpload(){if(activeUpload){activeUpload.abort();activeUpload=null}}
  fileInput.addEventListener("change",()=>choose(fileInput.files[0]));
  ["dragenter","dragover"].forEach(x=>drop.addEventListener(x,e=>{e.preventDefault();drop.classList.add("drag")}));
  ["dragleave","drop"].forEach(x=>drop.addEventListener(x,e=>{e.preventDefault();drop.classList.remove("drag")}));
  drop.addEventListener("drop",e=>choose(e.dataTransfer.files[0]));
  function choose(file){if(!file)return;if(!/\.(geojson|json)$/i.test(file.name)){toast("Выберите файл GeoJSON или JSON",true);return}cancelActiveUpload();resetSession();state.importId=null;state.file=file;$("fileLabel").textContent=file.name;drop.querySelector("small").textContent=formatBytes(file.size);upload.disabled=false}
  function formatBytes(n){if(n<1024)return `${n} Б`;if(n<1048576)return `${(n/1024).toFixed(1)} КиБ`;return `${(n/1048576).toFixed(1)} МиБ`}
  upload.addEventListener("click",()=>{if(!state.file)return;cancelActiveUpload();resetSession();const sessionAtStart=state.session;const data=new FormData();data.append("file",state.file);const xhr=new XMLHttpRequest(),bar=$("uploadProgress"),fill=bar.querySelector("i");activeUpload=xhr;bar.classList.remove("hidden");busy(upload,true,"Загрузка…");const job=taskStart("Загрузка файла",state.file.name,{determinate:true});xhr.upload.onprogress=e=>{if(e.lengthComputable){fill.style.width=`${e.loaded/e.total*100}%`;taskMode(job,{progress:e.loaded/e.total,detail:`${formatBytes(e.loaded)} из ${formatBytes(e.total)} · ${Math.round(e.loaded/e.total*100)}%`})}};xhr.upload.onload=()=>taskMode(job,{determinate:false,title:"Сервер разбирает файл",detail:"Проверка структуры и координат GeoJSON"});xhr.onerror=()=>{if(activeUpload===xhr)activeUpload=null;if(sessionAtStart===state.session)uploadFailed("Не удалось связаться с сервером")};xhr.onabort=()=>{if(activeUpload===xhr)activeUpload=null};xhr.onload=async()=>{if(activeUpload===xhr)activeUpload=null;if(sessionAtStart!==state.session)return;if(xhr.status<200||xhr.status>=300){let m="Файл не прошёл проверку";try{m=JSON.parse(xhr.responseText).message||m}catch(_){}uploadFailed(m);return}try{const data=JSON.parse(xhr.responseText);if(sessionAtStart!==state.session)return;state.importId=data.id;$("importId").value=data.id;fill.style.width="100%";toast(`Принято объектов: ${data.featureCount}`);await prepare(sessionAtStart)}catch(e){if(sessionAtStart===state.session)uploadFailed(e.message)}};xhr.open("POST","/api/v1/imports");xhr.send(data)});
  function uploadFailed(message){taskEnd();busy(upload,false);$("uploadProgress").classList.add("hidden");toast(message,true)}

  $("showExisting").addEventListener("click",()=>$("existingBox").classList.toggle("hidden"));
  $("connectButton").addEventListener("click",async()=>{const id=$("importId").value.trim();if(!/^[0-9a-f-]{36}$/i.test(id)){toast("Введите корректный UUID импорта",true);return}cancelActiveUpload();resetSession();state.importId=id;try{await prepare(state.session)}catch(e){toast(e.message,true)}});
  async function prepare(sessionAtStart){step("geometry");busy(upload,true,"Подготовка геометрии…");const job=taskStart("Подготовка геометрии","Сервер строит пространственные индексы и переводит координаты. Для больших файлов это занимает время.");try{await api(`/api/v1/imports/${state.importId}/geometry`,{method:"POST"});if(sessionAtStart!==state.session)return;taskMode(job,{title:"Загрузка объектов на карту",detail:"Получаем объекты с сервера"});await loadInput();if(sessionAtStart!==state.session)return;$("settingsCard").classList.remove("disabled");$("calculateButton").disabled=false;step("result");toast("Данные готовы к расчёту")}finally{if(sessionAtStart===state.session){busy(upload,false);taskEnd(job)}}}
  async function loadInput(){const sessionAtStart=state.session,importId=state.importId;state.input=[];const L=2000,MAX_PAGES=150,url=a=>`/api/v1/imports/${importId}/geometry/map?after=${a}&limit=${L}`,get=async a=>(await api(url(a))).json();
    let pages=[await get(-1)],more=pages[0].hasMore;
    if(more&&pages[0].nextAfter===L-1){
      // ordinals are dense in file order: the remaining pages are addressed directly and fetched several at a time
      const rest=[];let k=1,done=false,dense=true;
      while(!done&&dense&&k<MAX_PAGES){const batch=[];for(let i=0;i<6&&k+i<MAX_PAGES;i++)batch.push(get((k+i)*L-1));const got=await Promise.all(batch);
        for(let i=0;i<got.length;i++){const p=got[i],expected=(k+i+1)*L-1;if(p.hasMore&&(p.features.length!==L||p.nextAfter!==expected)){dense=false;break}rest.push(p);if(!p.hasMore){done=true;break}}
        k+=6}
      if(dense){pages=pages.concat(rest);more=!done}else{pages=[pages[0]];more=true}
    }
    if(more&&pages.length===1){let after=pages[0].nextAfter,pn=1;more=pages[0].hasMore;while(more&&pn++<MAX_PAGES){const p=await get(after);pages.push(p);after=p.nextAfter;more=p.hasMore}}
    if(sessionAtStart!==state.session)return;                 // a newer file/import replaced this one while the pages were loading
    for(const p of pages)for(const f of p.features)state.input.push(f);
    if(more)toast("На карте показаны первые 300 000 объектов");$("mapEmpty").classList.add("hidden");$("mapToolbar").classList.remove("hidden");$("layerPanel").classList.remove("hidden");$("mapStatus").classList.remove("hidden");fit();updateStatus()}

  document.querySelectorAll("[data-target-mode]").forEach(b=>b.addEventListener("click",()=>{document.querySelectorAll("[data-target-mode]").forEach(x=>x.classList.remove("selected"));b.classList.add("selected");$("targetOrdinals").classList.toggle("hidden",b.dataset.targetMode!=="selected")}));
  document.querySelectorAll("[data-calc-mode]").forEach(b=>b.addEventListener("click",()=>{document.querySelectorAll("[data-calc-mode]").forEach(x=>x.classList.remove("selected"));b.classList.add("selected");state.mode=b.dataset.calcMode;const pill=document.querySelector(".mode-pill");if(pill&&pill.lastChild)pill.lastChild.textContent=state.mode==="DEPTH"?" С глубиной":" 2D-расчёт"}));
  $("variantCount").addEventListener("input",e=>$("variantValue").value=e.target.value);
  $("calculateButton").addEventListener("click",calculate);
  async function calculate(){const sessionAtStart=state.session;const button=$("calculateButton"),selected=document.querySelector("[data-target-mode].selected").dataset.targetMode,body={maxVariants:Number($("variantCount").value),explain:true,mode:state.mode||"2D",strictCompleteness:$("strictCheck").checked};if(selected==="selected"){const values=$("targetOrdinals").value.split(",").map(x=>x.trim()).filter(Boolean);if(!values.length||values.some(x=>!/^\d+$/.test(x))){toast("Укажите номера ОКС через запятую",true);return}body.targetOrdinals=[...new Set(values.map(Number))]}busy(button,true,"Идёт расчёт… 0:00");const job=taskStart("Идёт расчёт","Строим трассы и проверяем ограничения",{kind:"calc",ping:true});const started=Date.now(),clock=setInterval(()=>{const seconds=Math.floor((Date.now()-started)/1000);button.textContent=`Идёт расчёт… ${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,"0")}`},1000);try{const r=await api(`/api/v1/imports/${state.importId}/outputs/geojson/link`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});const info=await r.json();if(sessionAtStart!==state.session)return;
      // the file stays on the server: the browser downloads it straight to disk, and only a result small enough for the map is read into the page
      state.download=info;state.calcMode=body.mode;const unproven=(info.unprovenOksIds||[]).join(",");   // whole-calculation summary, for the toast below only; showResults() sets state.unproven per the tab actually shown
      if(info.bytes>VIEW_LIMIT_BYTES){state.output=null;startDownload(info);toast(`Результат ${Math.round(info.bytes/1048576)} МБ слишком велик для карты — файл скачивается на диск`);step("result");return}
      const file=await api(info.downloadUrl);const text=await file.text();if(sessionAtStart!==state.session)return;
      state.output=JSON.parse(text);const summaries=summariesOf();state.variant=summaries[0].variant_id;showResults();fit();step("result");toast(unproven?`Рассчитано вариантов: ${summaries.length}. ОКС без маршрута: ${unproven}`:`Рассчитано вариантов: ${summaries.length}`,unproven?"warn":false)}catch(e){if(e.code==="SEARCH_INCOMPLETE"&&$("strictCheck").checked&&sessionAtStart===state.session){taskEnd(job);const again=await ask({title:"Маршрут для части ОКС не доказан",message:(/OKS \[([^\]]*)\]/.exec(e.message)?`Для ОКС ${/OKS \[([^\]]*)\]/.exec(e.message)[1]} маршрут не найден, но и отсутствие маршрута не доказано: поиск остановился на своём пределе.`:e.message)+"\n\nМожно рассчитать без строгой проверки: такие ОКС будут показаны на карте и в карточке результата как «маршрут не найден, отсутствие не доказано».",confirmText:"Рассчитать без проверки",cancelText:"Отмена"});if(again){$("strictCheck").checked=false;setTimeout(calculate,0)}}else toast(e.message,true)}finally{clearInterval(clock);taskEnd(job);busy(button,false);if(sessionAtStart!==state.session)button.disabled=true}}
  function summariesOf(){return (state.output?.features||[]).filter(f=>f.properties.object_type==="variant_summary").map(f=>f.properties).sort((a,b)=>a.rank-b.rank)}
  // OKS the plan left unconnected, with the reason (present when the result was requested with explain)
  let unconnectedNotes=new Map();
  function collectUnconnected(){unconnectedNotes=new Map();const s=summariesOf().find(x=>x.variant_id===state.variant);for(const n of (s&&s.unconnected_notes)||[])unconnectedNotes.set(String(n.id),n)}
  function showResults(){collectUnconnected();const summaries=summariesOf(),tabs=$("variantTabs");tabs.innerHTML="";summaries.forEach(s=>{const b=document.createElement("button");b.textContent=`Вариант ${s.rank}`;b.setAttribute("role","tab");b.setAttribute("aria-selected",String(s.variant_id===state.variant));b.classList.toggle("selected",s.variant_id===state.variant);b.onclick=()=>{state.variant=s.variant_id;showResults();draw()};tabs.appendChild(b)});const s=summaries.find(x=>x.variant_id===state.variant);if(!s)return;
    // Per the variant actually on screen, not the whole calculation: two variants can leave different OKS unproven, and a
    // colour or a notice for one must never leak onto the other's tab (state.unproven also drives oksState() below).
    state.unproven=(s.unproven_oks_ids||[]).join(",");
    $("scoreValue").textContent=number(s.score,3);$("rankBadge").textContent=`${s.rank} место`;$("resultPanel").querySelector("h2").textContent=state.calcMode==="DEPTH"?"Варианты · режим с глубиной":"Варианты подключения";$("costValue").textContent=money(s.construction_cost);$("penaltyValue").textContent=money(s.unconnected_penalty||0);$("totalValue").textContent=money(s.calculated_cost);const partCost=0.7*s.calculated_cost/25e6,partLength=0.3*s.new_network_length/100,parts=$("scoreParts");parts.innerHTML="";const row=(a,b)=>{const r=document.createElement("div"),t=document.createElement("dt"),v=document.createElement("dd");t.textContent=a;v.textContent=b;r.append(t,v);parts.append(r)};const f=document.createElement("div"),ft=document.createElement("dt");ft.className="formula";ft.textContent="S = 0,7 · C / 25 000 000 + 0,3 · L / 100";f.append(ft);parts.append(f);row("Вклад стоимости: 0,7 · "+number(s.calculated_cost/25e6,3),number(partCost,3));row("Вклад длины: 0,3 · "+number(s.new_network_length/100,2),number(partLength,3));$("scoreValue").closest(".score-card").title="Итоговый показатель по разделу 6 технического приложения: S = 0,7 · C / 25 000 000 + 0,3 · L / 100, где C — итоговая стоимость (строительство + штраф за неподключённые ОКС), L — длина новых участков в метрах. Чем меньше S, тем выше вариант.";$("lengthValue").textContent=`${number(s.new_network_length,1)} м`;$("chamberValue").textContent=features("heat_chamber").length;$("unconnectedValue").textContent=s.unconnected_oks_ids.length;
    const notice=$("resultNotice");
    if(state.unproven){notice.textContent=`Для ОКС ${state.unproven} маршрут не найден, отсутствие маршрута не доказано.`;notice.classList.remove("hidden")}
    else if(s.unconnected_oks_ids.length){notice.textContent=`Не подключены ${s.unconnected_oks_ids.length} ОКС: допустимого маршрута нет.`;notice.classList.remove("hidden")}
    else notice.classList.add("hidden");
    $("resultPanel").classList.remove("hidden");$("reopenResults").classList.add("hidden");draw();updateStatus()}
  function features(type){return (state.output?.features||[]).filter(f=>f.properties.variant_id===state.variant&&f.properties.object_type===type)}
  const number=(v,d=0)=>new Intl.NumberFormat("ru-RU",{maximumFractionDigits:d,minimumFractionDigits:d}).format(v);
  const money=v=>new Intl.NumberFormat("ru-RU",{style:"currency",currency:"RUB",maximumFractionDigits:0}).format(v);
  const VIEW_LIMIT_BYTES=120*1048576;
  function startDownload(info){const a=document.createElement("a");a.href=info.downloadUrl;a.download=info.fileName;document.body.appendChild(a);a.click();a.remove()}
  $("downloadButton").addEventListener("click",()=>{if(state.download)startDownload(state.download)});
  // Closing the panel only hides it: the calculation stays in state, so a small pill in its place brings it straight back
  // instead of forcing a recalculation. There is nothing to reopen until a result has actually been shown once.
  $("closeResults").onclick=()=>{$("resultPanel").classList.add("hidden");if(state.output)$("reopenResults").classList.remove("hidden")};
  $("reopenResults").onclick=()=>{if(!state.output)return;$("reopenResults").classList.add("hidden");showResults()};
  $("closeFeature").onclick=()=>$("featureCard").classList.add("hidden");

  $("layerToggle").onclick=()=>$("layerMenu").classList.toggle("hidden");document.querySelectorAll("[data-layer]").forEach(c=>c.onchange=()=>{state.layers[c.dataset.layer]=c.checked;draw()});
  $("zoomIn").onclick=()=>zoom(1.35,canvas.width/2,canvas.height/2);$("zoomOut").onclick=()=>zoom(1/1.35,canvas.width/2,canvas.height/2);$("fitMap").onclick=fit;
  function mercator(p){const lon=p[0],lat=Math.max(-85,Math.min(85,p[1]));return [(lon+180)/360,(1-Math.log(Math.tan(lat*Math.PI/180)+1/Math.cos(lat*Math.PI/180))/Math.PI)/2]}
  function eachPosition(geometry,fn){if(!geometry)return;const walk=a=>{if(Array.isArray(a)&&a.length>=2&&typeof a[0]==="number")fn(a);else if(Array.isArray(a))a.forEach(walk)};walk(geometry.coordinates)}
  // ---- map rendering: geometry is projected once, drawn in a few batched paths, culled to the viewport, thinned to
  // ---- pixel size, and panned/zoomed by transforming the last rendered bitmap so huge imports stay responsive.
  const prepCache=new WeakMap();
  function prep(f){let p=prepCache.get(f);if(p)return p;const g=f.geometry,t=f.properties.object_type;p={f,t,output:!!f.properties.variant_id,point:null,parts:[],poly:!!g.type.includes("Polygon"),minX:Infinity,minY:Infinity,maxX:-Infinity,maxY:-Infinity};
    const grow=(x,y)=>{if(x<p.minX)p.minX=x;if(x>p.maxX)p.maxX=x;if(y<p.minY)p.minY=y;if(y>p.maxY)p.maxY=y};
    if(g.type==="Point"){p.point=mercator(g.coordinates);grow(p.point[0],p.point[1])}
    else{const groups=g.type==="LineString"?[g.coordinates]:g.type==="Polygon"?g.coordinates:g.type==="MultiLineString"?g.coordinates:g.coordinates.flat(1);
      for(const line of groups){const a=new Float64Array(line.length*2);line.forEach((c,i)=>{const m=mercator(c);a[2*i]=m[0];a[2*i+1]=m[1];grow(m[0],m[1])});p.parts.push(a)}}
    prepCache.set(f,p);return p}
  let itemsKey="",items=[],itemsInput=null,itemsOutput=null;
  function currentItems(){const out=state.output,key=`${state.input.length}|${state.variant}|${!!out}|${Object.values(state.layers).join()}`;if(key===itemsKey&&itemsInput===state.input&&itemsOutput===out)return items;itemsKey=key;itemsInput=state.input;itemsOutput=out;
    const list=[];for(const f of state.input){if(!f.geometry)continue;const t=f.properties.object_type;if(t==="heat_network"?state.layers.network:t==="restriction"?state.layers.restriction:t==="oks_connection_point"?state.layers.oks:true)list.push(prep(f))}
    if(state.layers.result)for(const f of out?.features||[])if(f.geometry&&f.properties.variant_id===state.variant)list.push(prep(f));
    return items=list}
  function visibleFeatures(){return currentItems().map(p=>p.f)}
  function fit(){const list=currentItems();if(!list.length)return;let minX=Infinity,minY=Infinity,maxX=-Infinity,maxY=-Infinity;for(const p of list){if(p.minX<minX)minX=p.minX;if(p.maxX>maxX)maxX=p.maxX;if(p.minY<minY)minY=p.minY;if(p.maxY>maxY)maxY=p.maxY}state.view.cx=(minX+maxX)/2;state.view.cy=(minY+maxY)/2;const w=canvas.clientWidth||800,h=canvas.clientHeight||600;state.view.scale=Math.max(100,Math.min((w-90)/Math.max(maxX-minX,1e-8),(h-90)/Math.max(maxY-minY,1e-8)));draw()}
  let cache=null,cacheView=null,frame=0,blitFrame=0,idleTimer=0;
  function draw(){clearTimeout(idleTimer);if(frame)return;frame=requestAnimationFrame(()=>{frame=0;render()})}
  function interact(){if(!cache||cache.width!==canvas.width||cache.height!==canvas.height){draw();return}
    if(!blitFrame)blitFrame=requestAnimationFrame(()=>{blitFrame=0;blit()});clearTimeout(idleTimer);idleTimer=setTimeout(draw,140)}
  function blit(){const W=canvas.width,H=canvas.height,d=devicePixelRatio||1,v=state.view,s=cacheView,k=v.scale/s.scale;
    ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,W,H);
    ctx.setTransform(k,0,0,k,W/2*(1-k)+(s.cx-v.cx)*v.scale*d,H/2*(1-k)+(s.cy-v.cy)*v.scale*d);ctx.drawImage(cache,0,0);ctx.setTransform(1,0,0,1,0,0)}
  // colours come from the CSS variables of the active theme, so the map follows light / dark mode
  let palette=null;
  function readPalette(){const cs=getComputedStyle(document.documentElement),v=n=>cs.getPropertyValue(n).trim();
    palette={oksOk:v("--map-oks-ok"),oksDoubt:v("--map-oks-doubt"),gas:v("--map-gas"),cable:v("--map-cable"),rail:v("--map-rail"),fill:v("--map-restrict-fill"),edge:v("--map-restrict-line"),network:v("--map-network"),result:v("--map-result"),chamber:v("--map-chamber"),chamberNew:v("--map-chamber-new"),oks:v("--map-oks"),danger:v("--map-danger"),source:v("--map-source"),tech:v("--map-tech"),rim:v("--map-rim"),label:v("--map-label"),halo:v("--map-label-halo")}}
  const colours=()=>palette||(readPalette(),palette);
  // state of every OKS in the shown result: connected (an end of a new line), left without a connection, and the ones among those whose absence of a route is not proven
  let oksSets={out:null,key:"",connected:new Set(),lost:new Set(),doubt:new Set()};
  function oksState(){
    const out=state.output,key=out?`${state.variant}|${state.unproven}`:"";
    if(oksSets.out===out&&oksSets.key===key)return oksSets;
    const sets={out,key,connected:new Set(),lost:new Set(),doubt:new Set()};
    if(out){
      const summary=summariesOf().find(x=>x.variant_id===state.variant);
      for(const f of features("heat_network")){sets.connected.add(String(f.properties.start_node_id));sets.connected.add(String(f.properties.end_node_id))}
      for(const id of (summary&&summary.unconnected_oks_ids)||[])sets.lost.add(String(id));
      for(const id of String(state.unproven||"").split(",").filter(Boolean))sets.doubt.add(id);
    }
    return oksSets=sets}
  function oksColour(id,C){const s=oksState();if(s.lost.has(id))return s.doubt.has(id)?C.oksDoubt:C.danger;return s.connected.has(id)?C.oksOk:C.oks}
  function render(){const W=canvas.width,H=canvas.height,d=devicePixelRatio||1,v=state.view,k=v.scale*d;
    ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,W,H);
    const x0=v.cx-W/2/k,x1=v.cx+W/2/k,y0=v.cy-H/2/k,y1=v.cy+H/2/k,list=currentItems();
    const polys=new Path2D(),inLines=new Path2D(),gasLines=new Path2D(),cableLines=new Path2D(),railLines=new Path2D(),outLines=new Path2D(),pointList=[],PX=1.2;
    const trace=(path,a)=>{const n=a.length/2;let lx=0,ly=0;for(let i=0;i<n;i++){const x=(a[2*i]-v.cx)*k+W/2,y=(a[2*i+1]-v.cy)*k+H/2;if(i===0){path.moveTo(x,y);lx=x;ly=y}else if(i===n-1||Math.abs(x-lx)+Math.abs(y-ly)>=PX){path.lineTo(x,y);lx=x;ly=y}}};
    for(const p of list){if(p.maxX<x0||p.minX>x1||p.maxY<y0||p.minY>y1)continue;
      if(p.point){pointList.push(p);continue}
      if(p.poly){const wpx=(p.maxX-p.minX)*k,hpx=(p.maxY-p.minY)*k;if(wpx<PX&&hpx<PX){polys.rect((p.minX-v.cx)*k+W/2,(p.minY-v.cy)*k+H/2,PX,PX);continue}for(const a of p.parts)trace(polys,a)}
      else{const rt=p.f.properties.restriction_type,path=p.output?outLines:p.t!=="restriction"?inLines:rt==="gas_pipeline"?gasLines:rt==="power_cable"?cableLines:railLines;for(const a of p.parts)trace(path,a)}}
    ctx.lineJoin="round";ctx.lineCap="round";
    const C=colours();ctx.fillStyle=C.fill;ctx.fill(polys);ctx.strokeStyle=C.edge;ctx.lineWidth=d;ctx.stroke(polys);
    ctx.strokeStyle=C.rail;ctx.lineWidth=3*d;ctx.setLineDash([2*d,5*d]);ctx.stroke(railLines);ctx.strokeStyle=C.cable;ctx.lineWidth=2.4*d;ctx.setLineDash([8*d,5*d]);ctx.stroke(cableLines);ctx.setLineDash([]);ctx.strokeStyle=C.gas;ctx.lineWidth=3*d;ctx.stroke(gasLines);ctx.strokeStyle=C.network;ctx.lineWidth=2*d;ctx.stroke(inLines);
    ctx.strokeStyle=C.result;ctx.lineWidth=4*d;ctx.stroke(outLines);
    // markers shrink (and lose their white rim) as they get dense, so a whole city does not turn into a white blur
    const n=pointList.length,shrink=n>3000?.4:n>800?.6:n>250?.8:1,points=new Map();
    for(const p of pointList){const key=p.t==="heat_chamber"?(p.output?C.chamberNew:C.chamber):p.t==="oks_connection_point"?oksColour(String(p.f.properties.id),C):p.t==="source"?C.source:C.tech;let path=points.get(key);if(!path)points.set(key,path=new Path2D());const x=(p.point[0]-v.cx)*k+W/2,y=(p.point[1]-v.cy)*k+H/2,r=(p.t==="oks_connection_point"?5:4)*d*shrink;path.moveTo(x+r,y);path.arc(x,y,r,0,Math.PI*2)}
    ctx.strokeStyle=C.rim;ctx.lineWidth=2*d;for(const[color,path]of points){ctx.fillStyle=color;ctx.fill(path);if(n<=800)ctx.stroke(path)}
    // captions of the new chambers (why each one exists is in its card); only while few are in view, so they stay readable
    const labelled=pointList.filter(p=>p.f.properties.label),lost=pointList.filter(p=>p.t==="oks_connection_point"&&unconnectedNotes.has(String(p.f.properties.id)));
    if(state.layers.labels&&((labelled.length&&labelled.length<=400)||lost.length)){
      ctx.font=`${12*d}px system-ui,sans-serif`;ctx.textBaseline="middle";ctx.lineJoin="round";ctx.lineWidth=3.5*d;
      const placed=[];   // a caption is skipped when it would sit on one already drawn: zoom in and the rest appear
      for(const p of lost.concat(labelled)){const isLost=p.t==="oks_connection_point",x=(p.point[0]-v.cx)*k+W/2+9*d,y=(p.point[1]-v.cy)*k+H/2-9*d,text=isLost?`ОКС ${p.f.properties.id} · не подключён`:`${humanName(p.f.properties)} · ${String(p.f.properties.label).split("·").pop().trim()}`,w=ctx.measureText(text).width,h=14*d;
        if(placed.some(r=>x<r[0]+r[2]&&x+w>r[0]&&y-h/2<r[1]+r[3]&&y+h/2>r[1]))continue;
        placed.push([x,y-h/2,w,h]);ctx.strokeStyle=C.halo;ctx.strokeText(text,x,y);ctx.fillStyle=isLost?C.danger:C.label;ctx.fillText(text,x,y)}
    }
    if(!cache)cache=document.createElement("canvas");if(cache.width!==W||cache.height!==H){cache.width=W;cache.height=H}
    cache.getContext("2d").clearRect(0,0,W,H);cache.getContext("2d").drawImage(canvas,0,0);cacheView={cx:v.cx,cy:v.cy,scale:v.scale}}
  function zoom(factor,x,y){const before=[state.view.cx+(x-canvas.width/2)/state.view.scale/devicePixelRatio,state.view.cy+(y-canvas.height/2)/state.view.scale/devicePixelRatio];state.view.scale=Math.max(100,Math.min(4e9,state.view.scale*factor));state.view.cx=before[0]-(x-canvas.width/2)/state.view.scale/devicePixelRatio;state.view.cy=before[1]-(y-canvas.height/2)/state.view.scale/devicePixelRatio;interact()}
  canvas.addEventListener("wheel",e=>{e.preventDefault();const r=canvas.getBoundingClientRect();zoom(e.deltaY<0?1.18:1/1.18,(e.clientX-r.left)*devicePixelRatio,(e.clientY-r.top)*devicePixelRatio)},{passive:false});
  canvas.addEventListener("pointerdown",e=>{state.drag={x:e.clientX,y:e.clientY,cx:state.view.cx,cy:state.view.cy,moved:false};canvas.setPointerCapture(e.pointerId);canvas.classList.add("dragging")});canvas.addEventListener("pointermove",e=>{if(!state.drag)return;const dx=e.clientX-state.drag.x,dy=e.clientY-state.drag.y;state.drag.moved=state.drag.moved||Math.abs(dx)+Math.abs(dy)>3;state.view.cx=state.drag.cx-dx/state.view.scale;state.view.cy=state.drag.cy-dy/state.view.scale;interact()});canvas.addEventListener("pointerup",e=>{const moved=state.drag?.moved;state.drag=null;canvas.classList.remove("dragging");if(!moved)pick(e)});
  function segDistance(px,py,ax,ay,bx,by){const dx=bx-ax,dy=by-ay,l=dx*dx+dy*dy;let t=l?((px-ax)*dx+(py-ay)*dy)/l:0;t=t<0?0:t>1?1:t;return Math.hypot(px-(ax+t*dx),py-(ay+t*dy))}
  function pick(e){const r=canvas.getBoundingClientRect(),d=devicePixelRatio||1,v=state.view,k=v.scale*d,mx=v.cx+((e.clientX-r.left)*d-canvas.width/2)/k,my=v.cy+((e.clientY-r.top)*d-canvas.height/2)/k,tol=13*d/k;
    // markers win over lines, lines over areas: a click next to a chamber opens the chamber, not the building behind it
    const best=[null,null,null],dist=[tol,tol,tol];
    for(const p of currentItems()){if(mx<p.minX-tol||mx>p.maxX+tol||my<p.minY-tol||my>p.maxY+tol)continue;let dd=Infinity;const tier=p.point?0:p.poly?2:1;
      if(p.point)dd=Math.hypot(p.point[0]-mx,p.point[1]-my);
      else for(const a of p.parts)for(let q=0;q+3<a.length;q+=2)dd=Math.min(dd,segDistance(mx,my,a[q],a[q+1],a[q+2],a[q+3]));
      if(dd<dist[tier]){dist[tier]=dd;best[tier]=p.f}}
    const hit=best[0]||best[1]||best[2];if(hit)showFeature(hit)}
  function resize(){const d=devicePixelRatio||1,w=canvas.clientWidth,h=canvas.clientHeight;if(canvas.width!==Math.round(w*d)||canvas.height!==Math.round(h*d)){canvas.width=Math.round(w*d);canvas.height=Math.round(h*d)}draw()}
  new ResizeObserver(resize).observe(canvas);
  const RESTRICTIONS={oks:"здание (ОКС)",park:"парк",social_area:"социальный объект",prohibited_site:"запретная зона",water:"водный объект",railway:"железная дорога",road:"дорога",tram_tracks:"трамвайные пути",gas_pipeline:"газопровод",power_cable:"кабель"};
  // generated ids look like out_<import>_v1_chamber_5: the card and the map say "Новая камера №5" instead
  function humanName(p){const id=String(p.id),m=id.match(/_[vd]\d+_(chamber|node|net)_(\d+)$/);
    if(p.variant_id&&m){const n=m[2];return m[1]==="chamber"?`Новая камера №${n}`:m[1]==="node"?`Технический узел №${n}`:`Новый участок №${n}`}
    switch(p.object_type){case"oks_connection_point":return `ОКС ${id}`;case"heat_chamber":return `Существующая камера ${id}`;case"source":return `Источник ${id}`;case"heat_network":return `Существующая труба ${id}`;
      case"restriction":{const n=RESTRICTIONS[p.restriction_type]||p.restriction_type||`Ограничение ${id}`;return n.charAt(0).toUpperCase()+n.slice(1)}
      case"variant_summary":return `Итоги варианта`;default:return `ID ${id}`}}
  function kindName(p){if(p.variant_id){if(p.object_type==="heat_chamber")return "Новая тепловая камера";if(p.object_type==="heat_network")return "Новый участок сети"}return labels[p.object_type]||p.object_type}
  function showFeature(f){const p=f.properties;$("featureKind").textContent=kindName(p);$("featureName").textContent=humanName(p);const keys={id:"Идентификатор",ordinal:"Номер в файле",variant_id:"Вариант",diameter:p.object_type==="heat_chamber"?"Диаметр камеры":"Условный диаметр",flow_tph:"Расход, т/ч",length:"Длина, м",laying_method:"Прокладка",cost:"Стоимость, ₽",restriction_type:"Тип ограничения",depth_start:"Глубина в начале, м",depth_end:"Глубина в конце, м"},dl=$("featureProperties");dl.innerHTML="";const noteBox=$("featureNote"),lostNote=p.object_type==="oks_connection_point"?unconnectedNotes.get(String(p.id)):null,noteText=lostNote?lostNote.note:p.note;if(noteText){noteBox.innerHTML="";const head=document.createElement("b");head.textContent=lostNote?"Почему ОКС не подключён":"Почему здесь новая камера";const text=document.createElement("span");text.textContent=noteText;noteBox.append(head,text);noteBox.classList.toggle("problem",!!lostNote);noteBox.classList.remove("hidden")}else noteBox.classList.add("hidden");Object.entries(keys).forEach(([k,label])=>{if(p[k]===undefined||p[k]===null)return;const row=document.createElement("div"),dt=document.createElement("dt"),dd=document.createElement("dd");dt.textContent=label;dd.textContent=typeof p[k]==="number"?(Number.isInteger(p[k])?(k==="id"||k==="ordinal"||k==="diameter"?String(p[k]):number(p[k],0)):number(p[k],2)):p[k];row.append(dt,dd);dl.append(row)});$("featureCard").classList.remove("hidden")}
  function updateStatus(){const result=(state.output?.features||[]).filter(f=>f.geometry&&f.properties.variant_id===state.variant).length;$("mapStatus").textContent=`Исходных объектов: ${state.input.length}${result?` · В результате: ${result}`:""}`}
  // ---- theme (auto / light / dark), remembered per browser ----
  const themeButton=$("themeToggle"),media=window.matchMedia?matchMedia("(prefers-color-scheme: dark)"):null;
  const storedTheme=()=>{try{return localStorage.getItem("theme")||"light"}catch(_){return "light"}};
  function applyTheme(mode){const root=document.documentElement;if(mode==="light"||mode==="dark")root.setAttribute("data-theme",mode);else root.removeAttribute("data-theme");
    themeButton.title=`Тема: ${mode==="auto"?"как в системе":mode==="dark"?"тёмная":"светлая"} (нажмите, чтобы сменить)`;palette=null;draw()}
  themeButton.onclick=()=>{const order=["light","dark","auto"],next=order[(order.indexOf(storedTheme())+1)%3];try{localStorage.setItem("theme",next)}catch(_){}applyTheme(next);toast(`Тема: ${next==="auto"?"как в системе":next==="dark"?"тёмная":"светлая"}`)};
  if(media)(media.addEventListener?media.addEventListener("change",()=>{palette=null;draw()}):media.addListener(()=>{palette=null;draw()}));
  applyTheme(storedTheme());
  // ---- side panel can be hidden to give the map the whole window ----
  $("sidebarToggle").onclick=()=>{const w=$("workspace"),collapsed=w.classList.toggle("collapsed");$("sidebarToggle").setAttribute("aria-expanded",String(!collapsed));setTimeout(resize,260)};
  $("layerToggle").addEventListener("click",()=>$("layerToggle").setAttribute("aria-expanded",String(!$("layerMenu").classList.contains("hidden"))));
  // ---- keyboard on the map: arrows pan, + / - zoom, 0 fits everything ----
  canvas.addEventListener("keydown",e=>{const step=60/state.view.scale;let used=true;
    if(e.key==="+"||e.key==="=")zoom(1.35,canvas.width/2,canvas.height/2);else if(e.key==="-"||e.key==="_")zoom(1/1.35,canvas.width/2,canvas.height/2);else if(e.key==="0")fit();
    else if(e.key==="ArrowLeft"){state.view.cx-=step;interact()}else if(e.key==="ArrowRight"){state.view.cx+=step;interact()}else if(e.key==="ArrowUp"){state.view.cy-=step;interact()}else if(e.key==="ArrowDown"){state.view.cy+=step;interact()}else used=false;
    if(used)e.preventDefault()});
  if(innerWidth<900){$("layerMenu").classList.add("hidden");$("layerToggle").setAttribute("aria-expanded","false")}   // on a small screen the legend starts folded
  resize();
})();
