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
    const value=prompt(
      `设置 ${username} 的到期时间。\n输入本地时间，例如：2026-09-30 23:59\n输入 now 可立即到期。`
    );
    if(value===null)return;
    let date;
    if(value.trim().toLowerCase()==="now"){
      date=new Date();
    }else{
      date=new Date(value.trim().replace(" ","T"));
    }
    if(Number.isNaN(date.getTime())){
      alertMsg("到期时间格式无效","error");
      return;
    }
    if(!confirm(`确认把 ${username} 的到期时间设置为：\n${date.toLocaleString("zh-CN",{hour12:false})}？`))return;
    try{
      await api(`/admin/api/users/${id}/set-expiry`,{method:"POST",body:{expires_at:date.toISOString()}});
      alertMsg("用户到期时间已修改");
      loadUsers();loadOverview();
    }catch(e){alertMsg(e.message,"error")}
  }

  async function unbindDevice(id,username){
    if(!confirm(`确认解绑 ${username} 的当前设备并撤销全部登录会话？\n\n这不会清除免费体验领取记录。用户需要重新登录。`))return;
    try{
      const d=await api(`/admin/api/users/${id}/unbind-device`,{method:"POST",body:{}});
      alertMsg(`设备已解绑，撤销 ${d.revoked_sessions||0} 个会话`);
      loadUsers();
    }catch(e){alertMsg(e.message,"error")}
  }

  async function deleteUser(id,username){
    if(!confirm(`确定删除用户 ${username} (#${id})？\n\n此操作会删除该用户的登录会话、VIP 事件、模块记录和试用领取记录，无法恢复。`))return;
    const typed=prompt(`再次确认：请输入用户名 ${username}`);
    if(typed!==username){
      if(typed!==null)alertMsg("用户名不匹配，已取消删除","error");
      return;
    }
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
