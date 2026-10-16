'use strict';
let session = ''; // Memory only; credentials are never stored in browser storage.
let hasToken = false;
const $ = id => document.getElementById(id);
const events = {register:'注册',login:'密码登录',logout:'退出登录',token_rotate:'创建 / 重置 Token',token_auth:'Token 认证',tunnel_connect:'隧道连接',tunnel_disconnect:'隧道断开',domain_delete:'删除子域名',tunnel_rejected:'连接被拒绝'};
const outcomes = {success:'成功',failure:'失败',conflict:'冲突',closed:'已断开'};
const date = value => value ? new Date(value * 1000).toLocaleString() : '—';
function message(text, error = false) { $('message').textContent = text; $('message').classList.toggle('error',error); }
function reset() {session='';hasToken=false;$('dashboard').hidden=true;$('auth').hidden=false;$('new-token').hidden=true;$('token-value').value='';$('domains').replaceChildren();$('audit').replaceChildren();}
async function api(path, method='GET', body) {
  const headers = {};
  if(session) headers.Authorization = `Bearer ${session}`;
  if(body !== undefined) headers['Content-Type']='application/json';
  const response = await fetch(`/_tunnel/api/${path}`,{method,headers,body:body === undefined ? undefined : JSON.stringify(body),credentials:'omit',cache:'no-store',redirect:'error'});
  if(response.status===204) return null;
  const result=await response.json();
  if(!response.ok) {if(response.status===401&&session)reset();throw new Error(result.error || `请求失败 (${response.status})`);}
  return result;
}
async function run(button, action) {button.disabled=true;try{await action();}catch(error){message(error.message,true);}finally{button.disabled=false;}}
function cell(row,text,className) {const td=document.createElement('td');td.textContent=text;if(className)td.className=className;row.append(td);return td;}
async function refresh() {
  const [me,domains,audit] = await Promise.all([api('me'),api('domains'),api('audit')]);
  $('account').textContent=me.username;
  $('admin-link').hidden=!me.is_admin;
  $('audit-info').textContent=`显示最近 100 条，保存 ${audit.retention_days} 天。仅你自己的记录可见。`;
  hasToken=!!me.token_prefix;
  $('token-info').textContent=hasToken ? `当前 Token：${me.token_prefix}… · 创建于 ${date(me.token_created_at)}` : '尚未创建连接 Token。也可以直接使用账号密码连接。';
  $('rotate').textContent=hasToken ? '重置连接 Token' : '创建连接 Token';
  $('domain-heading').textContent=`子域名 · ${domains.domains.length} / ${domains.limit}`;
  $('domains').replaceChildren();$('domain-empty').hidden=domains.domains.length>0;
  for(const domain of domains.domains) {
    const tr=document.createElement('tr');cell(tr,domain.domain);cell(tr,domain.status==='online'?'在线':'离线',domain.status);cell(tr,date(domain.last_connected_at));
    const td=cell(tr,'');const button=document.createElement('button');button.textContent='删除';button.className='secondary';button.disabled=domain.status==='online';
    button.addEventListener('click',()=>run(button,async()=>{if(!confirm(`删除 ${domain.domain}？删除后其他账号可以使用此名称。`))return;await api(`domains/${encodeURIComponent(domain.name)}`,'DELETE');await refresh();message('子域名已删除。');}));td.append(button);$('domains').append(tr);
  }
  $('audit').replaceChildren();
  for(const event of audit.events) {const tr=document.createElement('tr');cell(tr,date(event.created_at));cell(tr,events[event.action]||event.action);cell(tr,outcomes[event.outcome]||event.outcome);cell(tr,event.domain||'—');$('audit').append(tr);}
}
function credentials(){const form=$('credentials');return {username:form.elements.username.value,password:form.elements.password.value};}
$('credentials').addEventListener('submit',event=>{event.preventDefault();run(event.submitter,async()=>{const result=await api('login','POST',credentials());session=result.session;$('credentials').elements.password.value='';$('auth').hidden=true;$('dashboard').hidden=false;await refresh();message(`已登录 ${result.username}。`);});});
$('register').addEventListener('click',()=>run($('register'),async()=>{if(!$('credentials').reportValidity())return;await api('register','POST',credentials());$('credentials').elements.password.value='';message('注册成功，请输入密码登录。');}));
$('logout').addEventListener('click',()=>run($('logout'),async()=>{await api('logout','POST',{});reset();message('已退出登录。');}));
$('rotate').addEventListener('click',()=>run($('rotate'),async()=>{if(hasToken&&!confirm('重置后旧 Token 立即失效，使用旧 Token 的隧道会被断开。继续？'))return;const result=await api('token','POST',{});$('token-value').value=result.token;$('new-token').hidden=false;await refresh();message('新 Token 已生成，请立即保存。');}));
$('copy').addEventListener('click',()=>run($('copy'),async()=>{await navigator.clipboard.writeText($('token-value').value);message('Token 已复制。');}));
$('refresh').addEventListener('click',()=>run($('refresh'),refresh));
if(location.protocol!=='https:'&&!['localhost','127.0.0.1','[::1]'].includes(location.hostname)) {message('请通过 HTTPS 打开此页面，避免明文传输账号密码。',true);$('auth').hidden=true;}
else api('config').then(config=>{$('register').hidden=!config.registration;}).catch(error=>message(error.message,true));
