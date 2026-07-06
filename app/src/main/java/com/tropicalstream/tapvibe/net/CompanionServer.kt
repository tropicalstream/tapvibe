package com.tropicalstream.tapvibe.net

import com.tropicalstream.tapvibe.music.MusicLibrary
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Companion web server: a phone/computer browser on the same Wi-Fi opens
 * http://<glasses-ip>:PORT and drags music onto the page. Uploads are copied into
 * the library as raw bytes (see MusicLibrary) so the audio container/encoding is
 * preserved exactly.
 */
class CompanionServer(
    port: Int,
    private val library: MusicLibrary,
    private val onChanged: () -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.POST && session.uri == "/upload" -> upload(session)
                session.method == Method.POST && session.uri == "/delete" -> delete(session)
                session.uri == "/list" -> json(listJson())
                session.uri == "/" || session.uri == "/index.html" ->
                    newFixedLengthResponse(Response.Status.OK, "text/html", PAGE)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (t: Throwable) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error: ${t.message}")
        }
    }

    private fun upload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)                                // multipart → temp files
        var saved = 0
        for ((field, tmpPath) in files) {
            val original = session.parameters[field]?.firstOrNull() ?: continue
            if (original.isBlank()) continue
            library.save(File(tmpPath), original)               // binary copy, keep extension
            saved++
        }
        onChanged()
        return json("""{"ok":true,"saved":$saved}""")
    }

    private fun delete(session: IHTTPSession): Response {
        // name comes as a query parameter (?name=…), parsed without a body.
        val name = session.parameters["name"]?.firstOrNull()
        val ok = name != null && library.delete(name)
        onChanged()
        return json("""{"ok":$ok}""")
    }

    private fun listJson(): String {
        val arr = JSONArray()
        library.tracks().forEach {
            arr.put(JSONObject().put("name", it.name).put("size", it.length()))
        }
        return arr.toString()
    }

    private fun json(s: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", s)

    companion object {
        const val PORT = 8080

        fun deviceIp(): String? {
            return try {
                for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in Collections.list(iface.inetAddresses)) {
                        if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress
                    }
                }
                null
            } catch (t: Throwable) {
                null
            }
        }

        // Single-page companion UI. No '$' or backticks (Kotlin string safety).
        private val PAGE = """
<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>TapVibe - Add Music</title>
<style>
  :root { --bg:#0b0d12; --card:#141821; --line:#232a36; --fg:#e7ecf3; --dim:#8b96a6; --acc:#22d3ee; --acc2:#a78bfa; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--fg); font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif; }
  .wrap { max-width:640px; margin:0 auto; padding:28px 18px 60px; }
  h1 { font-size:22px; margin:0 0 2px; letter-spacing:.5px; }
  h1 span { color:var(--acc); }
  .sub { color:var(--dim); font-size:13px; margin-bottom:22px; }
  .drop { border:2px dashed var(--line); border-radius:16px; padding:38px 20px; text-align:center;
          background:var(--card); transition:.15s; cursor:pointer; }
  .drop.hot { border-color:var(--acc); background:#101722; box-shadow:0 0 0 4px rgba(34,211,238,.12) inset; }
  .drop .big { font-size:16px; margin-bottom:6px; }
  .drop .small { color:var(--dim); font-size:12px; }
  .plus { font-size:30px; color:var(--acc); }
  ul { list-style:none; padding:0; margin:22px 0 0; }
  li { display:flex; align-items:center; gap:12px; background:var(--card); border:1px solid var(--line);
       border-radius:12px; padding:12px 14px; margin-bottom:10px; }
  li .nm { flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  li .sz { color:var(--dim); font-size:12px; }
  li .del { color:var(--dim); cursor:pointer; font-size:20px; line-height:1; padding:2px 6px; border-radius:8px; }
  li .del:hover { color:#ff6b6b; background:#1c232e; }
  .bar { height:4px; background:var(--line); border-radius:4px; overflow:hidden; margin-top:8px; }
  .bar > div { height:100%; width:0; background:linear-gradient(90deg,var(--acc),var(--acc2)); transition:.1s; }
  .empty { color:var(--dim); text-align:center; padding:24px 0; font-size:14px; }
  .foot { color:var(--dim); font-size:12px; text-align:center; margin-top:30px; }
</style></head>
<body><div class="wrap">
  <h1>Tap<span>Vibe</span></h1>
  <div class="sub">Drag music here to send it to your glasses. It appears in the on-glasses library instantly.</div>
  <div id="drop" class="drop">
    <div class="plus">+</div>
    <div class="big">Drop audio files or a whole album folder</div>
    <div class="small">or tap to choose - mp3, m4a, flac, wav, ogg</div>
    <div id="prog"></div>
  </div>
  <input id="file" type="file" accept="audio/*" multiple style="display:none">
  <ul id="list"></ul>
  <div class="foot">TapVibe companion - keep this tab open while uploading</div>
</div>
<script>
  var drop=document.getElementById('drop'), input=document.getElementById('file'),
      list=document.getElementById('list'), prog=document.getElementById('prog');
  drop.onclick=function(){ input.click(); };
  input.onchange=function(){ startUpload(Array.prototype.slice.call(input.files)); input.value=''; };
  ;['dragenter','dragover'].forEach(function(e){ drop.addEventListener(e,function(ev){ ev.preventDefault(); drop.classList.add('hot'); }); });
  ;['dragleave','drop'].forEach(function(e){ drop.addEventListener(e,function(ev){ ev.preventDefault(); drop.classList.remove('hot'); }); });
  drop.addEventListener('drop',function(ev){
    var dt=ev.dataTransfer; if(!dt) return;
    if(dt.items && dt.items.length && dt.items[0].webkitGetAsEntry){
      collectFromItems(dt.items, function(files){ startUpload(files); });
    } else if(dt.files){ startUpload(Array.prototype.slice.call(dt.files)); }
  });

  function isAudio(name){
    var n=name.toLowerCase();
    var e=['.mp3','.m4a','.aac','.flac','.wav','.ogg','.opus','.mp4','.3gp'];
    for(var i=0;i<e.length;i++){ if(n.slice(-e[i].length)===e[i]) return true; }
    return false;
  }
  function collectFromItems(items, done){
    var out=[]; var pending=1;
    function finish(){ if(--pending===0) done(out); }
    for(var i=0;i<items.length;i++){
      var e=items[i].webkitGetAsEntry && items[i].webkitGetAsEntry();
      if(e){ pending++; traverse(e,out,finish); }
    }
    finish();
  }
  function traverse(entry,out,cb){
    if(entry.isFile){ entry.file(function(f){ out.push(f); cb(); }, function(){ cb(); }); }
    else if(entry.isDirectory){
      var reader=entry.createReader(); var pending=1;
      function fin(){ if(--pending===0) cb(); }
      (function read(){
        reader.readEntries(function(ents){
          if(ents.length){ pending+=ents.length; for(var j=0;j<ents.length;j++) traverse(ents[j],out,fin); read(); }
          else fin();
        }, function(){ fin(); });
      })();
    } else cb();
  }
  function startUpload(files){
    var arr=files.filter(function(f){ return isAudio(f.name); });
    arr.sort(function(a,b){ return a.name.localeCompare(b.name); });
    if(!arr.length){
      prog.innerHTML='<div style="margin-top:12px;font-size:12px;color:#8b96a6">no audio files found</div>';
      setTimeout(function(){ prog.innerHTML=''; },2500); return;
    }
    next(arr,0,arr.length);
  }
  function next(arr,i,total){
    if(i>=arr.length){ prog.innerHTML=''; load(); return; }
    var f=arr[i];
    prog.innerHTML='<div style="margin-top:14px;font-size:12px">Uploading '+(i+1)+' of '+total+':  '+esc(f.name)+'</div><div class="bar"><div id="pb"></div></div>';
    var pb=document.getElementById('pb');
    var fd=new FormData(); fd.append('file',f,f.name);
    var x=new XMLHttpRequest(); x.open('POST','/upload');
    x.upload.onprogress=function(e){ if(e.lengthComputable) pb.style.width=Math.round(e.loaded/e.total*100)+'%'; };
    x.onload=function(){ next(arr,i+1,total); };
    x.onerror=function(){ next(arr,i+1,total); };
    x.send(fd);
  }
  function load(){
    fetch('/list').then(function(r){return r.json();}).then(function(t){
      if(!t.length){ list.innerHTML='<div class="empty">No tracks yet - drop some music above.</div>'; return; }
      list.innerHTML='';
      t.forEach(function(it){
        var li=document.createElement('li');
        li.innerHTML='<div class="nm">'+esc(it.name)+'</div><div class="sz">'+size(it.size)+'</div><div class="del">x</div>';
        li.querySelector('.del').onclick=function(){ del(it.name); };
        list.appendChild(li);
      });
    });
  }
  function del(name){ fetch('/delete?name='+encodeURIComponent(name),{method:'POST'}).then(function(){ load(); }); }
  function size(b){ if(b>1048576) return (b/1048576).toFixed(1)+' MB'; return Math.round(b/1024)+' KB'; }
  function esc(s){ return String(s).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];}); }
  load();
</script>
</body></html>
""".trimIndent()
    }
}
