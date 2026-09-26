"use strict";
const $=id=>document.getElementById(id);
const qsa=(selector,root=document)=>[...root.querySelectorAll(selector)];
const state={token:sessionStorage.getItem("rylux_agent_token")||"",me:null,prices:[],view:"overview",userPage:1,userPages:1,licensePage:1,licensePages:1,logPage:1,logPages:1,reveal:false,users:[],cards:[],selectedUserIds:new Set(),sessionsUserId:null,renewUser:null,busy:false};
const money=cents=>new Intl.NumberFormat("zh-CN",{style:"currency",currency:"CNY"}).format(Number(cents||0)/100);
const esc=value=>String(value??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
function fmt(value){if(!value)return "-";const date=new Date(value);return Number.isNaN(date.getTime())?String(value):date.toLocaleString("zh-CN",{hour12:false})}
function notify(message,type="ok"){ $("globalAlert").textContent=message;$("globalAlert").className=`alert alert-${type}`; }
function inlineError(id,message){$(id).textContent=message;$(id).classList.toggle("hidden",!message)}
function emptyRow(columns,message="暂无记录"){return `<tr><td class="empty" colspan="${columns}">${esc(message)}</td></tr>`}
async function api(path,method="GET",body){
  const headers={Accept:"application/json"};
  if(state.token)headers.Authorization=`Bearer ${state.token}`;
  if(body!==undefined)headers["Content-Type"]="application/json";
  const response=await fetch(path,{method,headers,body:body===undefined?undefined:JSON.stringify(body)});
  const data=await response.json().catch(()=>({}));
  if(!response.ok){
    if(response.status===401&&state.token)resetSession();
    const detail=typeof data.detail==="string"?data.detail:Array.isArray(data.detail)?data.detail.map(item=>item.msg).join("；"):null;
    throw new Error(detail||`请求失败 (${response.status})`);
  }
  return data;
}
function resetSession(){
  state.token="";state.me=null;state.users=[];state.cards=[];state.reveal=false;
  sessionStorage.removeItem("rylux_agent_token");
  $("dashboard").classList.add("hidden");$("loginPanel").classList.remove("hidden");
  qsa(".modal-backdrop").forEach(modal=>modal.classList.add("hidden"));
  ["generatedKeys","userPassword","currentPassword","newPassword","confirmPassword"].forEach(id=>$(id).value="");
  ["userBody","licenseBody","recentBody","logBody","balanceBody"].forEach(id=>$(id).replaceChildren());
}
function renderMe(){
  const me=state.me;if(!me)return;
  $("agentName").textContent=me.username;$("settingsUsername").textContent=me.username;$("settingsId").textContent=me.id;
  ["topBalance","balanceValue","settingsBalance"].forEach(id=>$(id).textContent=money(me.balance_cents));
  const canUsers=me.can_manage_users||me.can_extend_vip;
  ["usersNav","overviewUsersBtn","overviewUsersPanel"].forEach(id=>$(id).classList.toggle("hidden",!canUsers));
  ["licensesNav","overviewLicensesPanel","overviewRecentPanel"].forEach(id=>$(id).classList.toggle("hidden",!me.can_issue_cards));
  $("addUserBtn").classList.toggle("hidden",!me.can_manage_users);
  $("generateBtn").classList.toggle("hidden",!me.can_issue_cards);
  ["batchRenewDays","batchRenewBtn","userSelectedInfo","userSelectAll"].forEach(id=>$(id).classList.toggle("hidden",!me.can_extend_vip));
  $("settingsPermissions").textContent=[[me.can_manage_users,"管理用户"],[me.can_issue_cards,"发卡"],[me.can_extend_vip,"VIP 续期"]].filter(([enabled])=>enabled).map(([,name])=>name).join(" / ")||"暂未开通";
}
async function loadMe(){state.me=await api("/agent/api/me");renderMe()}
async function loadPrices(){
  const data=await api("/agent/api/prices");
  state.prices=(data.items||[]).filter(plan=>[30,90,180,365].includes(Number(plan.days))&&Number.isSafeInteger(plan.price_cents)&&plan.price_cents>=0);
  $("overviewPrices").innerHTML=state.prices.map(plan=>`<div class="price-card"><span>${esc(plan.name)} · ${esc(plan.days)} 天</span><strong>${esc(money(plan.price_cents))}</strong></div>`).join("")||'<p class="muted">暂无可用套餐，请联系管理员。</p>';
  ["genDays","renewDays"].forEach(id=>{
    const old=$(id).value;$(id).replaceChildren();
    state.prices.forEach(plan=>{const option=document.createElement("option");option.value=plan.days;option.textContent=`${plan.name} · ${plan.days} 天 · ${money(plan.price_cents)}`;$(id).append(option)});
    if(state.prices.some(plan=>String(plan.days)===old))$(id).value=old;
  });
}
function cardStatus(card){
  if(card.redeemed_at)return '<span class="badge badge-disabled">已兑换</span>';
  if(!card.enabled)return '<span class="badge badge-disabled">已禁用</span>';
  if(card.status==="expired"||(card.expires_at&&new Date(card.expires_at).getTime()<=Date.now()))return '<span class="badge badge-expired">已到期</span>';
  return '<span class="badge badge-active">可用</span>';
}
function userStatus(user){
  if(!user.enabled)return '<span class="badge badge-disabled">账号禁用</span>';
  if(!user.membership?.active)return '<span class="badge badge-expired">已到期</span>';
  return user.membership.kind==="trial"?'<span class="badge badge-expired">体验有效</span>':'<span class="badge badge-active">VIP 有效</span>';
}
function customer(card){return card.redeemed_username?`${card.redeemed_username}${card.redeemed_by_user_id?` (#${card.redeemed_by_user_id})`:""}`:card.redeemed_by_user_id?`#${card.redeemed_by_user_id}`:"-"}
async function loadOverview(){
  const data=await api("/agent/api/overview");
  Object.entries({userStatTotal:"total",userStatActive:"active",userStatExpired:"expired",userStatDisabled:"disabled",userStatToday:"registered_today"}).forEach(([id,key])=>$(id).textContent=data.stats?.[key]??0);
  Object.entries({statTotal:"total",statActive:"active",statExpired:"expired",statDisabled:"disabled"}).forEach(([id,key])=>$(id).textContent=data.licenses?.[key]??0);
  $("recentBody").innerHTML=(data.recent||[]).map(card=>`<tr><td>#${esc(card.id)}</td><td class="code">***${esc(card.key_hint)}</td><td>${esc(card.duration_days)} 天</td><td>${cardStatus(card)}</td><td>${esc(customer(card))}</td><td>${esc(fmt(card.redeemed_at))}</td></tr>`).join("")||emptyRow(6,"暂无兑换码记录");
}
function pager(prefix,data){
  state[`${prefix}Page`]=Number(data.page)||1;state[`${prefix}Pages`]=Math.max(1,Number(data.pages)||1);
  $(`${prefix}PagerInfo`).textContent=`第 ${state[`${prefix}Page`]} / ${state[`${prefix}Pages`]} 页 · 共 ${data.total} 条`;
  $(`${prefix}PrevBtn`).disabled=state[`${prefix}Page`]<=1;$(`${prefix}NextBtn`).disabled=state[`${prefix}Page`]>=state[`${prefix}Pages`];
}
async function loadUsers(){
  const query=new URLSearchParams({q:$("userSearchInput").value.trim(),status:$("userStatusFilter").value,page:state.userPage,page_size:30});
  const data=await api(`/agent/api/users?${query}`);state.users=data.items;
  $("userBody").innerHTML=data.items.map(user=>`<tr><td>${state.me.can_extend_vip?`<input type="checkbox" data-user-check="${esc(user.id)}" ${state.selectedUserIds.has(Number(user.id))?"checked":""} aria-label="选择 ${esc(user.username)}">`:""}</td><td>#${esc(user.id)}</td><td><strong>${esc(user.username)}</strong></td><td>${userStatus(user)}</td><td>${esc(fmt(user.membership?.expires_at))}</td><td>${esc(fmt(user.created_at))}</td><td>${esc(fmt(user.last_login_at))}</td><td>${esc(user.last_login_ip||"-")}</td><td><div class="actions">${state.me.can_manage_users?`<button class="btn ${user.enabled?"btn-danger":"btn-success"} btn-sm" data-user-toggle="${esc(user.id)}">${user.enabled?"禁用":"启用"}</button><button class="btn btn-ghost btn-sm" data-user-sessions="${esc(user.id)}">设备</button><button class="btn btn-danger btn-sm" data-user-delete="${esc(user.id)}">删除</button>`:""}${state.me.can_extend_vip?`<button class="btn btn-secondary btn-sm" data-renew="${esc(user.id)}">VIP 续期</button>`:""}</div></td></tr>`).join("")||emptyRow(9,"暂无所属用户");
  pager("user",data);
  qsa("[data-user-check]").forEach(check=>check.onchange=()=>{const id=Number(check.dataset.userCheck);if(check.checked)state.selectedUserIds.add(id);else state.selectedUserIds.delete(id);updateUserBatchControls()});
  updateUserBatchControls();
  qsa("[data-user-toggle]").forEach(button=>button.onclick=()=>run(async()=>{
    button.disabled=true;try{const user=state.users.find(item=>String(item.id)===button.dataset.userToggle);await api(`/agent/api/users/${user.id}/toggle`,"POST",{enabled:!user.enabled});notify("用户状态已更新");await loadUsers()}finally{button.disabled=false}
  }));
  qsa("[data-renew]").forEach(button=>button.onclick=()=>run(async()=>{
    state.renewUser=state.users.find(user=>String(user.id)===button.dataset.renew);
    await prepareCharge();$("renewUser").textContent=`为 ${state.renewUser.username} (#${state.renewUser.id}) 续期`;
    inlineError("renewError","");preview("renew");openModal("renewModal");
  }));
  qsa("[data-user-sessions]").forEach(button=>button.onclick=()=>run(()=>showUserSessions(Number(button.dataset.userSessions))));
  qsa("[data-user-delete]").forEach(button=>button.onclick=()=>run(async()=>{
    const user=state.users.find(item=>String(item.id)===button.dataset.userDelete);if(!user||!confirm(`确定删除所属用户 ${user.username} (#${user.id})？此操作无法撤销。`))return;
    await api(`/agent/api/users/${user.id}`,"DELETE");state.selectedUserIds.delete(Number(user.id));notify("所属用户已删除");await loadUsers();await loadOverview();
  }));
}
function updateUserBatchControls(){
  const count=state.selectedUserIds.size;$("userSelectedInfo").textContent=`已选 ${count} 个`;$("batchRenewBtn").disabled=!count;
  const boxes=qsa("[data-user-check]");$("userSelectAll").checked=boxes.length>0&&boxes.every(box=>box.checked);
  $("userSelectAll").indeterminate=boxes.some(box=>box.checked)&&!$("userSelectAll").checked;
}
async function batchRenewUsers(){
  const ids=[...state.selectedUserIds],days=Number($("batchRenewDays").value),plan=state.prices.find(item=>item.days===days);
  if(!ids.length||!plan)return;const debit=plan.price_cents*ids.length;
  if(debit>Number(state.me.balance_cents||0)){notify("余额不足，请联系管理员充值。","error");return}
  if(!confirm(`为 ${ids.length} 个所属用户各续期 ${plan.name}，共扣除 ${money(debit)}？`))return;
  try{const result=await api("/agent/api/users/batch-renew","POST",{user_ids:ids,days});state.selectedUserIds.clear();state.me.balance_cents=result.balance_cents;renderMe();notify(`已为 ${result.count} 个用户续期，扣除 ${money(result.debit_cents)}。`);await loadUsers();await loadOverview()}
  catch(error){notify(error.message,"error")}
}
async function showUserSessions(userId){
  const data=await api(`/agent/api/users/${userId}/sessions`);state.sessionsUserId=userId;
  $("userSessionsTitle").textContent=`${data.user.username} (#${userId}) · 登录设备`;
  $("userSessionsSummary").textContent=`最后登录：${fmt(data.user.last_login_at)} · IP：${data.user.last_login_ip||"-"} · 设备：${data.user.device_hint||"-"}`;
  $("userSessionsBody").innerHTML=data.items.map(item=>`<tr><td>#${esc(item.id)}</td><td>${esc(fmt(item.created_at))}</td><td>${esc(fmt(item.last_seen_at))}</td><td>${esc(fmt(item.expires_at))}</td><td>${esc(item.ip_address||"-")}</td><td class="code">${esc(item.device_hint||"-")}</td><td>${item.revoked?"已撤销":"有效"}</td></tr>`).join("")||emptyRow(7,"暂无登录会话");
  openModal("userSessionsModal");
}
async function loadLicenses(){
  const query=new URLSearchParams({q:$("searchInput").value.trim(),status:$("statusFilter").value,page:state.licensePage,page_size:30,reveal:state.reveal?"true":"false"});
  const data=await api(`/agent/api/licenses?${query}`);state.cards=data.items;
  $("toggleRevealBtn").textContent=state.reveal?"隐藏完整兑换码":"显示完整兑换码";
  $("copyPageBtn").disabled=!state.reveal||!data.items.some(card=>card.key_value);
  $("licenseBody").innerHTML=data.items.map(card=>`<tr><td>#${esc(card.id)}</td><td class="code">${esc(state.reveal&&card.key_value?card.key_value:`***${card.key_hint||""}`)}</td><td>${esc(card.duration_days)} 天</td><td>${esc(card.label||"-")}</td><td>${cardStatus(card)}</td><td>${card.is_paid?"已收款":"未收款"}</td><td>${esc(fmt(card.expires_at))}</td><td>${esc(customer(card))}</td><td>${esc(fmt(card.redeemed_at))}</td><td><div class="actions">${state.reveal&&card.key_value?`<button class="btn btn-ghost btn-sm" data-copy-card="${esc(card.id)}">复制</button>`:""}${state.me.can_issue_cards?`<button class="btn ${card.enabled?"btn-danger":"btn-success"} btn-sm" data-card-toggle="${esc(card.id)}" ${card.redeemed_at?"disabled":""}>${card.enabled?"禁用":"启用"}</button>`:""}</div></td></tr>`).join("")||emptyRow(10,"暂无匹配兑换码");
  pager("license",data);
  qsa("[data-copy-card]").forEach(button=>button.onclick=()=>copy(state.cards.find(card=>String(card.id)===button.dataset.copyCard)?.key_value));
  qsa("[data-card-toggle]").forEach(button=>button.onclick=()=>run(async()=>{
    button.disabled=true;try{const card=state.cards.find(item=>String(item.id)===button.dataset.cardToggle);await api(`/agent/api/licenses/${card.id}/toggle`,"POST",{enabled:!card.enabled});notify("兑换码状态已更新");await loadLicenses()}finally{button.disabled=false}
  }));
}
async function loadLogs(){
  const data=await api(`/agent/api/logs?page=${state.logPage}&page_size=40`);
  $("logBody").innerHTML=data.items.map(row=>`<tr><td>#${esc(row.id)}</td><td class="code">${esc(row.action)}</td><td>${esc(row.target||"-")}</td><td class="wrap-cell">${esc(row.details||"-")}</td><td>${esc(row.ip_address||"-")}</td><td>${esc(fmt(row.created_at))}</td></tr>`).join("")||emptyRow(6,"暂无操作日志");pager("log",data);
}
async function loadBalance(){
  const data=await api("/agent/api/balance-events");
  $("balanceBody").innerHTML=data.items.map(row=>`<tr><td>#${esc(row.id)}</td><td class="${Number(row.delta_cents)>0?"amount-credit":"amount-debit"}">${Number(row.delta_cents)>0?"+":""}${esc(money(row.delta_cents))}</td><td>${esc(money(row.balance_after_cents))}</td><td class="wrap-cell">${esc(row.reason||"-")}</td><td>${esc(fmt(row.created_at))}</td></tr>`).join("")||emptyRow(5,"暂无余额流水");
}
const loaders={overview:loadOverview,users:loadUsers,licenses:loadLicenses,logs:loadLogs,settings:loadBalance};
async function showView(name){
  if(!state.me)return;
  if(name==="users"&&!state.me.can_manage_users&&!state.me.can_extend_vip)name="overview";
  if(name==="licenses"&&!state.me.can_issue_cards)name="overview";
  if(!loaders[name])return;
  state.view=name;
  Object.keys(loaders).forEach(view=>$(`view-${view}`).classList.toggle("hidden",view!==name));
  qsa(".nav-btn").forEach(button=>button.classList.toggle("active",button.dataset.view===name));
  $("pageTitle").textContent={overview:"数据概览",users:"用户 / VIP",licenses:"兑换码",logs:"操作日志",settings:"账号设置"}[name];
  $("sidebar").classList.remove("open");$("menuBtn").setAttribute("aria-expanded","false");
  await loadMe();await loaders[name]();
}
async function run(action){try{await action()}catch(error){if(state.me)notify(error.message,"error");else inlineError("loginAlert",error.message)}}
let modalReturnFocus=null;
function openModal(id){modalReturnFocus=document.activeElement;$(id).classList.remove("hidden");$(id).querySelector("input,select,textarea,button")?.focus()}
function closeModal(id){if(state.busy)return;$(id).classList.add("hidden");modalReturnFocus?.focus()}
async function prepareCharge(){await Promise.all([loadMe(),loadPrices()])}
function preview(kind){
  const generating=kind==="generate",days=Number($(generating?"genDays":"renewDays").value),quantity=generating?Number($("genQty").value):1;
  const plan=state.prices.find(item=>Number(item.days)===days),valid=plan&&Number.isInteger(quantity)&&quantity>=1&&quantity<=100;
  const total=valid?plan.price_cents*quantity:0,balance=Number(state.me?.balance_cents||0),affordable=valid&&total<=balance;
  $(generating?"generatePreview":"renewPreview").textContent=valid?`单价 ${money(plan.price_cents)} × ${quantity}${generating?" 张":" 人"}，本次扣款 ${money(total)}。当前余额 ${money(balance)}${affordable?`，扣款后 ${money(balance-total)}`:"，余额不足，请联系管理员充值"}。`:"请选择套餐并填写有效数量。";
  $(generating?"confirmGenerate":"confirmRenew").disabled=state.busy||!affordable;
  return valid&&affordable?{days,quantity}:null;
}
async function submitCharge(kind,event){
  event.preventDefault();if(state.busy)return;
  const selection=preview(kind);if(!selection)return;
  const generating=kind==="generate",errorId=generating?"generateError":"renewError",button=$(generating?"confirmGenerate":"confirmRenew");
  inlineError(errorId,"");state.busy=true;button.disabled=true;
  try{
    const result=await api(generating?"/agent/api/licenses/generate":`/agent/api/users/${state.renewUser.id}/extend`,"POST",generating?{...selection,label:$("genLabel").value.trim(),paid:$("genPaid").checked}:{days:selection.days});
    if(result.balance_cents!==undefined){state.me.balance_cents=result.balance_cents;renderMe()}
    state.busy=false;closeModal(generating?"generateModal":"renewModal");
    if(generating){$("generatedKeys").value=result.keys.join("\n");$("generatedInfo").textContent=`${selection.days} 天套餐 · 共 ${result.keys.length} 张 · 卡片到期 ${fmt(result.expires_at)}`;openModal("resultModal");notify("兑换码已生成并扣款，请保存兑换码。")}
    else notify(`已为 ${state.renewUser.username} 增加 ${selection.days} 天 VIP，并扣除对应套餐金额。`);
    await run(async()=>{await loadMe();await loaders[state.view]()});
  }catch(error){inlineError(errorId,error.message);if(!state.me)inlineError("loginAlert",error.message)}
  finally{state.busy=false;preview(kind)}
}
async function copy(value){if(!value)return;try{await navigator.clipboard.writeText(value);notify("兑换码已复制")}catch{notify("复制失败，请显示完整兑换码后手动复制。","error")}}
function downloadKeys(){const blob=new Blob([$("generatedKeys").value],{type:"text/plain;charset=utf-8"}),url=URL.createObjectURL(blob),link=document.createElement("a");link.href=url;link.download=`RYLUX-codes-${new Date().toISOString().slice(0,10)}.txt`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
async function initialize(){
  await loadMe();await loadPrices();
  $("loginPanel").classList.add("hidden");$("dashboard").classList.remove("hidden");inlineError("loginAlert","");
  state.userPage=state.licensePage=state.logPage=1;await showView("overview");
}
$("loginForm").onsubmit=async event=>{
  event.preventDefault();$("loginBtn").disabled=true;inlineError("loginAlert","");
  try{const data=await api("/api/v1/auth/login","POST",{username:$("username").value.trim(),password:$("password").value});state.token=data.token;sessionStorage.setItem("rylux_agent_token",state.token);$("password").value="";await initialize()}
  catch(error){resetSession();inlineError("loginAlert",error.message)}finally{$("loginBtn").disabled=false}
};
$("logoutBtn").onclick=async()=>{try{await api("/api/v1/auth/logout","POST",{})}catch{}resetSession()};
qsa("[data-view]").forEach(button=>button.onclick=()=>run(()=>showView(button.dataset.view)));
qsa("[data-jump]").forEach(button=>button.onclick=()=>run(()=>showView(button.dataset.jump)));
$("menuBtn").onclick=()=>{$("sidebar").classList.toggle("open");$("menuBtn").setAttribute("aria-expanded",String($("sidebar").classList.contains("open")))};
qsa("[data-close]").forEach(button=>button.onclick=()=>closeModal(button.dataset.close));
qsa(".modal-backdrop").forEach(modal=>modal.onclick=event=>{if(event.target===modal)closeModal(modal.id)});
document.addEventListener("keydown",event=>{
  const modal=qsa(".modal-backdrop:not(.hidden)")[0];if(!modal)return;
  if(event.key==="Escape")closeModal(modal.id);
  if(event.key==="Tab"){const controls=qsa("button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea",modal),first=controls[0],last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus()}}
});
$("addUserBtn").onclick=()=>{inlineError("addUserError","");openModal("addUserModal")};
$("addUserForm").onsubmit=async event=>{
  event.preventDefault();if(state.busy)return;state.busy=true;$("confirmAddUser").disabled=true;inlineError("addUserError","");
  try{await api("/agent/api/users","POST",{username:$("newUsername").value.trim(),password:$("userPassword").value});event.target.reset();state.busy=false;closeModal("addUserModal");notify("用户已创建，可通过 VIP 续期或兑换码开通使用时间。");state.userPage=1;await run(loadUsers)}catch(error){inlineError("addUserError",error.message)}finally{state.busy=false;$("confirmAddUser").disabled=false}
};
$("generateBtn").onclick=()=>run(async()=>{await prepareCharge();inlineError("generateError","");preview("generate");openModal("generateModal")});
$("genDays").onchange=$("genQty").oninput=()=>preview("generate");$("renewDays").onchange=()=>preview("renew");
$("generateForm").onsubmit=event=>submitCharge("generate",event);$("renewForm").onsubmit=event=>submitCharge("renew",event);
$("toggleRevealBtn").onclick=()=>run(async()=>{state.reveal=!state.reveal;await loadLicenses()});
$("copyPageBtn").onclick=()=>copy(state.cards.map(card=>card.key_value).filter(Boolean).join("\n"));
$("exportCsvBtn").onclick=()=>run(async()=>{
  const query=new URLSearchParams({q:$("searchInput").value.trim(),status:$("statusFilter").value});
  const response=await fetch(`/agent/api/licenses/export.csv?${query}`,{headers:{Authorization:`Bearer ${state.token}`}});
  if(!response.ok){const data=await response.json().catch(()=>({}));throw new Error(data.detail||`导出失败 (${response.status})`)}
  const url=URL.createObjectURL(await response.blob()),link=document.createElement("a");link.href=url;link.download=`RYLUX-agent-cards-${new Date().toISOString().slice(0,10)}.csv`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});
$("copyKeysBtn").onclick=()=>copy($("generatedKeys").value);$("downloadKeysBtn").onclick=downloadKeys;
for(const [prefix,search,filter,searchButton,resetButton,loader] of [["user","userSearchInput","userStatusFilter","userSearchBtn","userResetBtn",loadUsers],["license","searchInput","statusFilter","searchBtn","resetSearchBtn",loadLicenses]]){
  $(searchButton).onclick=()=>run(async()=>{state[`${prefix}Page`]=1;await loader()});
  $(resetButton).onclick=()=>{$(search).value="";$(filter).value="";$(searchButton).click()};
  $(search).onkeydown=event=>{if(event.key==="Enter")$(searchButton).click()};
}
for(const [prefix,loader] of [["user",loadUsers],["license",loadLicenses],["log",loadLogs]]){
  for(const [direction,delta] of [["Prev",-1],["Next",1]])$(`${prefix}${direction}Btn`).onclick=()=>run(async()=>{const next=state[`${prefix}Page`]+delta;if(next>=1&&next<=state[`${prefix}Pages`]){state[`${prefix}Page`]=next;await loader()}});
}
$("refreshBalanceBtn").onclick=()=>run(async()=>{await loadMe();await loadBalance()});
$("userSelectAll").onchange=()=>{qsa("[data-user-check]").forEach(check=>{check.checked=$("userSelectAll").checked;const id=Number(check.dataset.userCheck);if(check.checked)state.selectedUserIds.add(id);else state.selectedUserIds.delete(id)});updateUserBatchControls()};
$("batchRenewBtn").onclick=()=>run(batchRenewUsers);
$("unbindDeviceBtn").onclick=()=>run(async()=>{
  if(!state.sessionsUserId||!confirm("确定解除该用户的设备绑定并退出所有已登录设备？"))return;
  await api(`/agent/api/users/${state.sessionsUserId}/unbind-device`,"POST",{});notify("设备绑定已解除，相关会话已退出。" );await showUserSessions(state.sessionsUserId);
});
$("passwordForm").onsubmit=async event=>{
  event.preventDefault();if($("newPassword").value!==$("confirmPassword").value){notify("两次输入的新密码不一致。","error");return}
  $("savePasswordBtn").disabled=true;
  try{await api("/agent/api/change-password","POST",{current_password:$("currentPassword").value,new_password:$("newPassword").value});event.target.reset();resetSession();inlineError("loginAlert","");$("loginAlert").textContent="密码已更新，请使用新密码登录。";$("loginAlert").className="alert alert-ok"}catch(error){notify(error.message,"error")}finally{$("savePasswordBtn").disabled=false}
};
if(state.token)initialize().catch(error=>{resetSession();inlineError("loginAlert",error.message)});
