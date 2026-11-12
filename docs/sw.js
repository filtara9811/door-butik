const C='db-v1';
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
self.addEventListener('fetch',e=>{
 const r=e.request;if(r.method!=='GET')return;
 const u=new URL(r.url);if(u.origin!==location.origin||u.pathname.endsWith('version.json'))return;
 e.respondWith((async()=>{
  const cache=await caches.open(C);
  const net=fetch(r).then(res=>{if(res&&res.ok)cache.put(r,res.clone()).catch(()=>{});return res});
  const hit=async()=>(await cache.match(r,{ignoreSearch:true}))||(await cache.match('./index.html',{ignoreSearch:true}))||(await cache.match('./',{ignoreSearch:true}));
  const cached=await hit();
  if(!cached)return net;
  const t=new Promise(res=>setTimeout(()=>res(null),4000));
  try{const res=await Promise.race([net,t]);return res||cached}catch(_){return cached}
 })())});
