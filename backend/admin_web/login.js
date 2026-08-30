const form=document.getElementById("loginForm");
const err=document.getElementById("error");
form.addEventListener("submit",async(e)=>{
  e.preventDefault(); err.classList.add("hidden");
  const fd=new FormData(form);
  try{
    const r=await fetch("/admin/api/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({username:fd.get("username"),password:fd.get("password")})});
    const data=await r.json();
    if(!r.ok) throw new Error(data.detail||"登录失败");
    location.href=data.redirect||"/admin";
  }catch(ex){err.textContent=ex.message;err.classList.remove("hidden")}
});
