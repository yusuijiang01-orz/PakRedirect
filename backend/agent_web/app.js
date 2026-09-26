const $=id=>document.getElementById(id);
let token=sessionStorage.getItem("rylux_agent_token")||"";
const message=(s)=>{$("message").textContent=s};
async function api(path,method="GET",body){
  const r=await fetch(path,{method,headers:{"Accept":"application/json","Content-Type":"application/json",...(token?{"Authorization":"Bearer "+token}:{})},body:body?JSON.stringify(body):undefined});
  const d=await r.json();if(!r.ok)throw new Error(d.detail||"请求失败");return d;
}
async function refresh(){
  try{
    const a=await api("/agent/api/me");
    $("loginPanel").hidden=true;$("dashboard").hidden=false;
    $("agentName").textContent=a.username;$("quota").textContent=a.quota_days;
    $("cardsPanel").hidden=!a.can_issue_cards;$("usersPanel").hidden=!(a.can_manage_users||a.can_extend_vip);
    const ref=await api("/api/v1/referrals/me");$("inviteCode").textContent=ref.invite_code;
    if(a.can_issue_cards){
      const cards=await api("/agent/api/licenses");$("cards").replaceChildren();
      cards.items.forEach(c=>{const row=document.createElement("div");row.className="info-row";
        row.textContent=`#${c.id} ${c.key_value||c.key_hint} · ${c.duration_days} 天 · ${c.redeemed_at?"已兑换":c.enabled?"可用":"已停用"}`;$("cards").append(row)});
    }
    if(a.can_manage_users||a.can_extend_vip){
      const users=await api("/agent/api/users");$("users").replaceChildren();
      users.items.forEach(u=>{
        const row=document.createElement("div");row.className="info-row";
        const label=document.createElement("span");label.textContent=`#${u.id} ${u.username} · ${u.enabled?"启用":"停用"} · 到期 ${u.membership.expires_at}`;
        row.append(label);
        if(a.can_manage_users){const toggle=document.createElement("button");toggle.className="btn btn-ghost btn-sm";toggle.textContent=u.enabled?"停用":"启用";
        toggle.onclick=async()=>{try{await api(`/agent/api/users/${u.id}/toggle`,"POST",{enabled:!u.enabled});refresh()}catch(e){message(e.message)}};row.append(toggle)}
        if(a.can_extend_vip){
          const renew=document.createElement("button");renew.className="btn btn-secondary btn-sm";renew.textContent="续期 30 天";
          renew.onclick=async()=>{try{await api(`/agent/api/users/${u.id}/extend`,"POST",{days:30});refresh()}catch(e){message(e.message)}};row.append(renew);
        }
        $("users").append(row);
      });
    }
  }catch(e){token="";sessionStorage.removeItem("rylux_agent_token");$("loginPanel").hidden=false;$("dashboard").hidden=true;message(e.message)}
}
$("loginForm").onsubmit=async e=>{e.preventDefault();try{const d=await api("/api/v1/auth/login","POST",{username:$("username").value,password:$("password").value});token=d.token;sessionStorage.setItem("rylux_agent_token",token);$("password").value="";message("");refresh()}catch(err){message(err.message)}};
$("cardsForm").onsubmit=async e=>{e.preventDefault();try{const d=await api("/agent/api/licenses/generate","POST",{days:Number($("cardDays").value),quantity:Number($("cardQty").value),label:$("cardLabel").value,paid:$("cardPaid").checked});$("generated").value=d.keys.join("\n");message("发卡成功，请保存兑换码");refresh()}catch(err){message(err.message)}};
$("logout").onclick=async()=>{try{await api("/api/v1/auth/logout","POST",{})}catch(_){}token="";sessionStorage.removeItem("rylux_agent_token");$("dashboard").hidden=true;$("loginPanel").hidden=false};
if(token)refresh();
