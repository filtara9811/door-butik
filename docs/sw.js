const C='db-v2';
const PRE=['./','./index.html','./sys1.js','./sys2.js'];
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(C).then(c=>Promise.all(PRE.map(u=>c.add(u).catch(()=>{})))))});
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{
 const r=e.request;if(r.method!=='GET')return;
 const u=new URL(r.url);if(u.origin!==location.origin||u.pathname.endsWith('version.json'))return;
 e.respondWith((async()=>{
  const cache=await caches.open(C);
  const net=fetch(r).then(res=>{if(res&&res.ok)cache.put(r,res.clone()).catch(()=>{});return res});
  const hit=async()=>(await cache.match(r,{ignoreSearch:true}))||(r.mode==='navigate'?((await cache.match('./index.html',{ignoreSearch:true}))||(await cache.match('./',{ignoreSearch:true}))):undefined);
  const cached=await hit();
  if(!cached)return net;
  const t=new Promise(res=>setTimeout(()=>res(null),3000));
  try{const res=await Promise.race([net,t]);return res||cached}catch(_){return cached}
 })())});
