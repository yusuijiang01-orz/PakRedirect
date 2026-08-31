(()=>{
  "use strict";

  function ensureSessionModal(){
    let modal=document.getElementById("userSessionModal");
    if(modal)return modal;
    modal=document.createElement("div");
    modal.id="userSessionModal";
    modal.className="modal-backdrop hidden";
    modal.innerHTML=`<div class="modal">
      <div class="modal-head"><h3 id="userSessionTitle">登录 / IP 记录</h3><button class="btn btn-ghost btn-sm" id="userSessionClose">关闭</button></div>
      <div class="modal-body"><textarea class="keys" id="userSessionText" readonly></textarea></div>
      <div class="modal-foot"><button class="btn btn-primary" id="userSessionDone">完成</button></div>
    </div>`;
    document.body.appendChild(modal);
    const close=()=>modal.classList.add("hidden");
    document.getElementById("userSessionClose").onclick=close;
    document.getElementById("userSessionDone").onclick=close;
    modal.addEventListener("click",e=>{if(e.target===modal)close()});
    return modal;
  }

  function ensureActionModal(){
    let modal=document.getElementById("userActionModal");
    if(modal)return modal;
    modal=document.createElement("div");
    modal.id="userActionModal";
    modal.className="modal-backdrop hidden";
    modal.innerHTML=`<div class="modal" role="dialog" aria-modal="true" aria-labelledby="userActionTitle">
      <div class="modal-head">
        <div>
          <h3 id="userActionTitle">用户操作</h3>
          <div class="muted" id="userActionSubtitle" style="margin-top:5px;font-size:12px"></div>
        </div>
        <button class="btn btn-ghost btn-sm" id="userActionClose">关闭</button>
      </div>
      <div class="modal-body" id="userActionBody"></div>
      <div class="modal-foot">
        <button class="btn btn-ghost" id="userActionCancel">取消</button>
        <button class="btn btn-primary" id="userActionConfirm">确认</button>
      </div>
    </div>`;
    document.body.appendChild(modal);
    const cancel=()=>{
      const resolve=modal._resolve;
      modal._resolve=null;
      modal.classList.add("hidden");
      if(resolve)resolve(null);
    };
    document.getElementById("userActionClose").onclick=cancel;
    document.getElementById("userActionCancel").onclick=cancel;
    modal.addEventListener("click",e=>{if(e.target===modal)cancel()});
    document.addEventListener("keydown",e=>{
      if(e.key==="Escape"&&!modal.classList.contains("hidden"))cancel();
    });
    return modal;
  }

  function openActionDialog({title,subtitle="",bodyHtml="",confirmText="确认",danger=false,onReady=null}){
    const modal=ensureActionModal();
    const titleEl=document.getElementById("userActionTitle");
    const subtitleEl=document.getElementById("userActionSubtitle");
    const body=document.getElementById("userActionBody");
    const confirm=document.getElementById("userActionConfirm");
    titleEl.textContent=title;
    subtitleEl.textContent=subtitle;
    subtitleEl.classList.toggle("hidden",!subtitle);
    body.innerHTML=bodyHtml;
    confirm.textContent=confirmText;
    confirm.className=`btn ${danger?"btn-danger":"btn-primary"}`;
    confirm.disabled=false;
    modal.classList.remove("hidden");
    return new Promise(resolve=>{
      modal._resolve=resolve;
      confirm.onclick=()=>{
        if(confirm.disabled)return;
        modal._resolve=null;
        modal.classList.add("hidden");
        resolve(true);
      };
      if(onReady)onReady({modal,body,confirm,resolve:(value)=>{
        modal._resolve=null;
        modal.classList.add("hidden");
        resolve(value);
      }});
    });
  }

  function toLocalInputValue(date){
    const pad=n=>String(n).padStart(2,"0");
    return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }

  async function showSessions(id,username){
    try{
      const d=await api(`/admin/api/users/${id}/sessions?limit=30`);
      const lines=[];
      lines.push(`用户：${d.user.username}`);
      lines.push(`最后登录：${fmt(d.user.last_login_at)}`);
      lines.push(`最后登录 IP：${d.user.last_login_ip||"-"}`);
      lines.push(`当前设备摘要：${d.user.device_hint||"-"}`);
      lines.push("");
      lines.push("最近登录会话：");
      if(!d.items.length){
        lines.push("暂无会话记录");
      }else{
        d.items.forEach((s,i)=>{
          lines.push(`${i+1}. IP=${s.ip_address||"-"}  创建=${fmt(s.created_at)}  最后活动=${fmt(s.last_seen_at)}  ${s.revoked?"已撤销":"有效"}`);
          lines.push(`   设备=${s.device_hint||"-"}  会话到期=${fmt(s.expires_at)}`);
        });
      }
      const modal=ensureSessionModal();
      document.getElementById("userSessionTitle").textContent=`${username} · 登录 / IP 记录`;
      document.getElementById("userSessionText").value=lines.join("\n");
      modal.classList.remove("hidden");
    }catch(e){alertMsg(e.message,"error")}
  }

  async function setExpiry(id,username){
    let chosen=null;
    const ok=await openActionDialog({
      title:"修改会员到期时间",
      subtitle:`用户：${username} · #${id}`,
      confirmText:"保存修改",
      bodyHtml:`
        <div class="form-group">
          <label for="userExpiryInput">新的到期时间</label>
          <input class="input" id="userExpiryInput" type="datetime-local" step="60">
        </div>
        <div class="muted" style="font-size:12px;line-height:1.7;margin-top:8px">可以延长，也可以缩短会员时长。时间按你当前浏览器的本地时区填写。</div>
        <div style="margin-top:14px"><button class="btn btn-secondary btn-sm" id="expireNowBtn" type="button">设为立即到期</button></div>`,
      onReady:({confirm,resolve})=>{
        const input=document.getElementById("userExpiryInput");
        const nowBtn=document.getElementById("expireNowBtn");
        const defaultDate=new Date(Date.now()+30*24*60*60*1000);
        input.value=toLocalInputValue(defaultDate);
        input.focus();
        const update=()=>{confirm.disabled=!input.value};
        input.addEventListener("input",update);
        update();
        nowBtn.onclick=()=>{
          chosen=new Date();
          resolve("now");
        };
        confirm.onclick=()=>{
          if(!input.value)return;
          const date=new Date(input.value);
          if(Number.isNaN(date.getTime())){
            alertMsg("到期时间格式无效","error");
            return;
          }
          chosen=date;
          resolve("save");
        };
      }
    });
    if(!ok||!chosen)return;
    try{
      await api(`/admin/api/users/${id}/set-expiry`,{method:"POST",body:{expires_at:chosen.toISOString()}});
      alertMsg(`已将 ${username} 的到期时间修改为 ${chosen.toLocaleString("zh-CN",{hour12:false})}`);
      loadUsers();loadOverview();
    }catch(e){alertMsg(e.message,"error")}
  }

  async function unbindDevice(id,username){
    const ok=await openActionDialog({
      title:"解绑当前设备",
      subtitle:`用户：${username} · #${id}`,
      confirmText:"确认解绑",
      danger:true,
      bodyHtml:`
        <div class="alert alert-warn" style="margin-top:0">解绑后，该用户当前登录会话会全部失效，需要重新登录。</div>
        <div class="muted" style="line-height:1.8">此操作只清除当前设备绑定，不会清除免费体验领取记录，也不会修改会员到期时间。</div>`
    });
    if(!ok)return;
    try{
      const d=await api(`/admin/api/users/${id}/unbind-device`,{method:"POST",body:{}});
      alertMsg(`设备已解绑，撤销 ${d.revoked_sessions||0} 个会话`);
      loadUsers();
    }catch(e){alertMsg(e.message,"error")}
  }

  async function deleteUser(id,username){
    let typed="";
    const ok=await openActionDialog({
      title:"删除用户",
      subtitle:`用户：${username} · #${id}`,
      confirmText:"永久删除",
      danger:true,
      bodyHtml:`
        <div class="alert alert-error" style="margin-top:0"><strong>此操作无法恢复。</strong><br>该用户的登录会话、VIP 事件、模块记录和试用领取记录都会一起删除。</div>
        <div class="form-group">
          <label for="deleteUserConfirmInput">请输入用户名 <span class="code">${esc(username)}</span> 进行确认</label>
          <input class="input" id="deleteUserConfirmInput" autocomplete="off" placeholder="输入完整用户名">
        </div>`,
      onReady:({confirm})=>{
        const input=document.getElementById("deleteUserConfirmInput");
        const update=()=>{
          typed=input.value;
          confirm.disabled=typed!==username;
        };
        input.addEventListener("input",update);
        update();
        input.focus();
      }
    });
    if(!ok||typed!==username)return;
    try{
      await api(`/admin/api/users/${id}`,{method:"DELETE"});
      alertMsg(`用户 ${username} 已删除`);
      loadUsers();loadOverview();
    }catch(e){alertMsg(e.message,"error")}
  }

  function enhanceRows(){
    const body=document.getElementById("userBody");
    if(!body)return;
    body.querySelectorAll("tr").forEach(row=>{
      if(row.dataset.adminControls==="1")return;
      const first=row.cells&&row.cells[0];
      const nameCell=row.cells&&row.cells[1];
      const actions=row.querySelector(".actions");
      if(!first||!nameCell||!actions)return;
      const match=(first.textContent||"").match(/#(\d+)/);
      if(!match)return;
      const id=match[1];
      const username=(nameCell.textContent||"").trim();
      row.dataset.adminControls="1";

      const expiry=document.createElement("button");
      expiry.className="btn btn-secondary btn-sm";
      expiry.textContent="修改到期";
      expiry.onclick=()=>setExpiry(id,username);

      const sessions=document.createElement("button");
      sessions.className="btn btn-ghost btn-sm";
      sessions.textContent="登录 / IP";
      sessions.onclick=()=>showSessions(id,username);

      const unbind=document.createElement("button");
      unbind.className="btn btn-ghost btn-sm";
      unbind.textContent="解绑设备";
      unbind.onclick=()=>unbindDevice(id,username);

      const del=document.createElement("button");
      del.className="btn btn-danger btn-sm";
      del.textContent="删除";
      del.onclick=()=>deleteUser(id,username);

      actions.appendChild(expiry);
      actions.appendChild(sessions);
      actions.appendChild(unbind);
      actions.appendChild(del);
    });
  }

  function start(){
    const body=document.getElementById("userBody");
    if(!body){setTimeout(start,100);return}
    new MutationObserver(enhanceRows).observe(body,{childList:true,subtree:true});
    enhanceRows();
  }

  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",start);
  else start();
})();
