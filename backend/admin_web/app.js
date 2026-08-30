const state={csrf:"",username:"",mustChange:false,view:"overview",licensePage:1,licensePages:1,logPage:1,logPages:1,days:30};

const $=(id)=>document.getElementById(id);
const qs=(s,root=document)=>root.querySelector(s);
const qsa=(s,root=document)=>[...root.querySelectorAll(s)];

function fmt(v){
  if(!v)return "-";
  const d=new Date(v);
  return isNaN(d)?v:d.toLocaleString("zh-CN",{hour12:false});
}
function esc(v){
  return String(v??"").replace(/[&<>"']/g,m=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[m]));
}
function badge(s){
  const map={active:["有效","badge-active"],disabled:["已禁用","badge-disabled"],expired:["已到期","badge-expired"]};
  const [t,c]=map[s]||[s,""];
  return `<span class="badge ${c}">${t}</span>`;
}
function alertMsg(msg,type="ok"){
  const el=$("globalAlert"); el.textContent=msg; el.className=`alert alert-${type}`;
  clearTimeout(alertMsg.t); alertMsg.t=setTimeout(()=>el.classList.add("hidden"),3500);
}
async function api(url,opt={}){
  const headers=Object.assign({"Accept":"application/json"},opt.headers||{});
  if(opt.body && typeof opt.body!=="string"){headers["Content-Type"]="application/json";opt.body=JSON.stringify(opt.body)}
  if(state.csrf && opt.method && opt.method!=="GET")headers["X-CSRF-Token"]=state.csrf;
  const r=await fetch(url,Object.assign({},opt,{headers}));
  if(r.status===401){location.href="/admin/login";throw new Error("登录已过期")}
  const ct=r.headers.get("content-type")||"";
  const data=ct.includes("application/json")?await r.json():await r.text();
  if(!r.ok){
    if(r.status===428){state.mustChange=true;showView("settings")}
    throw new Error(data.detail||data||`请求失败 (${r.status})`);
  }
  return data;
}
async function loadMe(){
  const me=await api("/admin/api/me");
  state.csrf=me.csrf;state.username=me.username;state.mustChange=me.must_change_password;
  $("adminName").textContent=me.username;$("settingsUsername").value=me.username;
  $("passwordNotice").classList.toggle("hidden",!state.mustChange);
  if(state.mustChange)showView("settings");
}
function showView(name){
  if(state.mustChange && name!=="settings")name="settings";
  state.view=name;
  ["overview","licenses","logs","settings"].forEach(v=>$(`view-${v}`).classList.toggle("hidden",v!==name));
  qsa(".nav-btn").forEach(b=>b.classList.toggle("active",b.dataset.view===name));
  const titles={overview:"数据概览",licenses:"卡密管理",logs:"操作日志",settings:"系统设置"};
  $("pageTitle").textContent=titles[name];
  $("sidebar").classList.remove("open");
  if(name==="overview")loadOverview();
  if(name==="licenses")loadLicenses();
  if(name==="logs")loadLogs();
}
async function loadOverview(){
  try{
    const d=await api("/admin/api/overview");
    $("statTotal").textContent=d.stats.total;$("statActive").textContent=d.stats.active;
    $("statExpired").textContent=d.stats.expired;$("statDisabled").textContent=d.stats.disabled;
    $("statToday").textContent=d.stats.verified_today;
    $("recentBody").innerHTML=d.recent.length?d.recent.map(r=>`<tr>
      <td>#${r.id}</td><td class="code">***${esc(r.key_hint)}</td><td>${esc(r.label||"-")}</td>
      <td>${badge(r.status)}</td><td>${fmt(r.expires_at)}</td><td>${fmt(r.last_seen_at)}</td><td>${esc(r.last_seen_ip||"-")}</td>
    </tr>`).join(""):`<tr><td class="empty" colspan="7">暂无验证记录</td></tr>`;
  }catch(e){alertMsg(e.message,"error")}
}
async function loadLicenses(){
  try{
    const q=encodeURIComponent($("searchInput").value.trim()), status=encodeURIComponent($("statusFilter").value);
    const d=await api(`/admin/api/licenses?q=${q}&status=${status}&page=${state.licensePage}&page_size=30`);
    state.licensePages=d.pages;
    $("licenseBody").innerHTML=d.items.length?d.items.map(r=>{
      const toggleText=r.enabled?"禁用":"启用",toggleClass=r.enabled?"btn-danger":"btn-success";
      return `<tr>
        <td>#${r.id}</td><td class="code">***${esc(r.key_hint)}</td><td>${esc(r.label||"-")}</td>
        <td>${badge(r.status)}</td><td>${fmt(r.expires_at)}</td><td>${fmt(r.last_seen_at)}</td><td>${esc(r.last_seen_ip||"-")}</td>
        <td><div class="actions">
          <button class="btn ${toggleClass} btn-sm" data-toggle="${r.id}" data-enabled="${r.enabled?0:1}">${toggleText}</button>
          <select class="select" data-extend-select="${r.id}">
            <option value="1">+1天</option><option value="7">+7天</option><option value="30" selected>+30天</option>
            <option value="90">+90天</option><option value="180">+180天</option><option value="360">+360天</option>
          </select>
          <button class="btn btn-secondary btn-sm" data-extend="${r.id}">续期</button>
        </div></td>
      </tr>`;
    }).join(""):`<tr><td class="empty" colspan="8">暂无匹配卡密</td></tr>`;
    $("pagerInfo").textContent=`第 ${d.page} / ${d.pages} 页 · 共 ${d.total} 条`;
    $("prevBtn").disabled=state.licensePage<=1;$("nextBtn").disabled=state.licensePage>=state.licensePages;
    bindLicenseActions();
  }catch(e){alertMsg(e.message,"error")}
}
function bindLicenseActions(){
  qsa("[data-toggle]").forEach(b=>b.onclick=async()=>{
    try{
      await api(`/admin/api/licenses/${b.dataset.toggle}/toggle`,{method:"POST",body:{enabled:b.dataset.enabled==="1"}});
      alertMsg("状态已更新");loadLicenses();loadOverview();
    }catch(e){alertMsg(e.message,"error")}
  });
  qsa("[data-extend]").forEach(b=>b.onclick=async()=>{
    const id=b.dataset.extend,sel=qs(`[data-extend-select="${id}"]`),days=Number(sel.value);
    if(!confirm(`确认给 #${id} 续期 ${days} 天？`))return;
    try{
      await api(`/admin/api/licenses/${id}/extend`,{method:"POST",body:{days}});
      alertMsg(`已续期 ${days} 天`);loadLicenses();loadOverview();
    }catch(e){alertMsg(e.message,"error")}
  });
}
async function loadLogs(){
  try{
    const d=await api(`/admin/api/logs?page=${state.logPage}&page_size=40`);
    state.logPages=d.pages;
    $("logBody").innerHTML=d.items.length?d.items.map(r=>`<tr>
      <td>#${r.id}</td><td class="code">${esc(r.action)}</td><td>${esc(r.target||"-")}</td>
      <td>${esc(r.details||"-")}</td><td>${esc(r.ip_address||"-")}</td><td>${fmt(r.created_at)}</td>
    </tr>`).join(""):`<tr><td class="empty" colspan="6">暂无操作日志</td></tr>`;
    $("logPagerInfo").textContent=`第 ${d.page} / ${d.pages} 页 · 共 ${d.total} 条`;
    $("logPrevBtn").disabled=state.logPage<=1;$("logNextBtn").disabled=state.logPage>=state.logPages;
  }catch(e){alertMsg(e.message,"error")}
}
function openModal(id){$(id).classList.remove("hidden")}
function closeModal(id){$(id).classList.add("hidden")}
async function generate(){
  const quantity=Number($("genQty").value),label=$("genLabel").value.trim();
  if(!Number.isInteger(quantity)||quantity<1||quantity>1000){alertMsg("生成数量必须为 1-1000","error");return}
  $("confirmGenerate").disabled=true;$("confirmGenerate").textContent="生成中...";
  try{
    const d=await api("/admin/api/licenses/generate",{method:"POST",body:{days:state.days,quantity,label}});
    closeModal("generateModal");$("generatedKeys").value=d.keys.join("\n");
    $("generatedExpiry").textContent=`到期时间：${fmt(d.expires_at)} · 共 ${d.keys.length} 张`;
    openModal("resultModal");loadLicenses();loadOverview();
  }catch(e){alertMsg(e.message,"error")}
  finally{$("confirmGenerate").disabled=false;$("confirmGenerate").textContent="生成"}
}
function downloadText(){
  const txt=$("generatedKeys").value;if(!txt)return;
  const blob=new Blob([txt],{type:"text/plain;charset=utf-8"}),a=document.createElement("a");
  a.href=URL.createObjectURL(blob);a.download=`PakRedirect-keys-${new Date().toISOString().slice(0,10)}.txt`;a.click();URL.revokeObjectURL(a.href);
}
async function copyKeys(){
  try{await navigator.clipboard.writeText($("generatedKeys").value);alertMsg("已复制全部卡密")}
  catch{alertMsg("复制失败，请手动复制","error")}
}
async function saveCredentials(e){
  e.preventDefault();const fd=new FormData(e.target);
  const body={current_password:fd.get("current_password"),username:fd.get("username"),new_password:fd.get("new_password"),confirm_password:fd.get("confirm_password")};
  try{
    const d=await api("/admin/api/credentials",{method:"POST",body});
    alertMsg("管理员账号和密码已更新");state.username=d.username;state.mustChange=false;
    e.target.reset();$("settingsUsername").value=d.username;$("adminName").textContent=d.username;
    await loadMe();showView("overview");
  }catch(ex){alertMsg(ex.message,"error")}
}
async function logout(){
  try{await api("/admin/api/logout",{method:"POST",body:{}})}catch(e){}
  location.href="/admin/login";
}
function bind(){
  qsa(".nav-btn").forEach(b=>b.onclick=()=>showView(b.dataset.view));
  qsa("[data-jump]").forEach(b=>b.onclick=()=>showView(b.dataset.jump));
  $("menuBtn").onclick=()=>$("sidebar").classList.toggle("open");
  $("logoutBtn").onclick=logout;
  $("generateBtn").onclick=()=>openModal("generateModal");
  qsa("[data-close]").forEach(b=>b.onclick=()=>closeModal(b.dataset.close));
  qsa(".day-btn").forEach(b=>b.onclick=(e)=>{e.preventDefault();state.days=Number(b.dataset.days);qsa(".day-btn").forEach(x=>x.classList.toggle("active",x===b))});
  $("confirmGenerate").onclick=generate;$("copyKeysBtn").onclick=copyKeys;$("downloadKeysBtn").onclick=downloadText;
  $("searchBtn").onclick=()=>{state.licensePage=1;loadLicenses()};
  $("resetSearchBtn").onclick=()=>{$("searchInput").value="";$("statusFilter").value="";state.licensePage=1;loadLicenses()};
  $("searchInput").addEventListener("keydown",e=>{if(e.key==="Enter"){$("searchBtn").click()}});
  $("prevBtn").onclick=()=>{if(state.licensePage>1){state.licensePage--;loadLicenses()}};
  $("nextBtn").onclick=()=>{if(state.licensePage<state.licensePages){state.licensePage++;loadLicenses()}};
  $("logPrevBtn").onclick=()=>{if(state.logPage>1){state.logPage--;loadLogs()}};
  $("logNextBtn").onclick=()=>{if(state.logPage<state.logPages){state.logPage++;loadLogs()}};
  $("credentialForm").addEventListener("submit",saveCredentials);
  $("exportBtn").onclick=()=>{const q=encodeURIComponent($("searchInput").value.trim()),s=encodeURIComponent($("statusFilter").value);location.href=`/admin/api/licenses/export.csv?q=${q}&status=${s}`};
  qsa(".modal-backdrop").forEach(m=>m.addEventListener("click",e=>{if(e.target===m)closeModal(m.id)}));
}
(async()=>{bind();try{await loadMe();if(!state.mustChange)await loadOverview()}catch(e){alertMsg(e.message,"error")}})();
