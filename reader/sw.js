const C='reader-v1',A=['./','index.html','style.css','app.js','manifest.json','icon.svg'];
self.addEventListener('install',e=>e.waitUntil(caches.open(C).then(c=>c.addAll(A))));
self.addEventListener('fetch',e=>{
  const u=new URL(e.request.url);
  if(u.origin!==location.origin)return; // API и видео не кешируем
  e.respondWith(fetch(e.request).catch(()=>caches.match(e.request)));
});
