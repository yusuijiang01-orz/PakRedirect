/* CoraFlow electron/main.cjs numeric OCR pipeline, Tesseract.js/core 7.0.0.
 * Models are copied byte-for-byte from CoraFlow's ocr-cache, never downloaded.
 * RGBA canvas storage replaces Electron's BGRA nativeImage storage only. */
let worker, currentLanguage;
async function recognize(request) {
  const language = request.kind === 'number' ? 'eng'
    : request.language === 'chi_sim+eng' ? 'eng+chi_sim'
      : request.language === 'vie+eng' ? 'eng+vie' : request.language;
  if (!worker || currentLanguage !== language) {
    if (worker) await worker.terminate();
    worker = null;
    worker = await Tesseract.createWorker(language.split('+'), Tesseract.OEM.LSTM_ONLY, {
      workerPath: new URL('worker.min.js', location.href).href,
      corePath: new URL('.', location.href).href,
      langPath: new URL('.', location.href).href,
      gzip: false, cacheMethod: 'none', workerBlobURL: false,
    });
    currentLanguage = language;
  }
  const image = new Image();
  image.src = request.image;
  await image.decode();
  const scale = Math.max(1, Math.min(Math.max(1, Math.min(4, request.scale || 2)), Math.sqrt(8000000 / (image.width * image.height))));
  const canvas = document.createElement('canvas');
  canvas.width = image.width; canvas.height = image.height;
  const ctx = canvas.getContext('2d', {willReadFrequently:true});
  ctx.drawImage(image, 0, 0);
  if(scale>1.05) {
    const resized=coraflowResize(ctx.getImageData(0,0,canvas.width,canvas.height),Math.round(image.width*scale),Math.round(image.height*scale));
    canvas.width=resized.width;canvas.height=resized.height;ctx.putImageData(resized,0,0);
  }
  await worker.setParameters({tessedit_pageseg_mode: request.kind === 'number' ? '7' : '6', tessedit_char_whitelist: request.kind === 'number' ? '0123456789.,/%-' : ''});
  // Block output provides real word boxes; document confidence remains unmodified.
  const run = () => worker.recognize(canvas, {}, {text:true, blocks:true});
  let result = await run(), confidence = result.data.confidence || 0;
  if (request.kind === 'number' && confidence < 85) {
    const source = ctx.getImageData(0,0,canvas.width,canvas.height), pixels = canvas.width*canvas.height;
    const gray = new Uint8Array(pixels), normalized = new Uint8Array(pixels);
    let minimum=255, maximum=0;
    for(let i=0;i<pixels;i++) { const o=i*4,v=Math.round((source.data[o]+source.data[o+1]+source.data[o+2])/3);gray[i]=v;minimum=Math.min(minimum,v);maximum=Math.max(maximum,v); }
    for(let i=0;i<pixels;i++) normalized[i]=Math.max(0,Math.min(255,Math.round((gray[i]-minimum)*255/Math.max(1,maximum-minimum))));
    for(const mode of ['amber-text','gray','dark-text','light-text']) {
      const output=ctx.createImageData(canvas.width,canvas.height);
      for(let i=0;i<pixels;i++) {
        const o=i*4,v=normalized[i],red=source.data[o],green=source.data[o+1],blue=source.data[o+2];
        const p=mode==='amber-text'?(red>blue+35&&green>blue+15&&red>85?0:255):mode==='gray'?v:mode==='dark-text'?(v>=140?0:255):(v>=140?255:0);
        output.data[o]=output.data[o+1]=output.data[o+2]=p;output.data[o+3]=255;
      }
      ctx.putImageData(output,0,0);
      const candidate=await run(), score=candidate.data.confidence||0;
      if(score>confidence){result=candidate;confidence=score;}
      if(confidence>=85) break;
    }
  }
  const words=[];
  for(const block of result.data.blocks||[]) for(const paragraph of block.paragraphs||[]) for(const line of paragraph.lines||[]) for(const word of line.words||[]) {
    const b=word.bbox; if(b) words.push({text:word.text,confidence:word.confidence||0,x:(b.x0+b.x1)/2/scale,y:(b.y0+b.y1)/2/scale});
  }
  canvas.width=canvas.height=1;
  return {text:String(result.data.text||'').trim(),confidence,words,engine:'tesseract.js-7.0.0'};
}
window.runOcr = async function(request) {
  try { RyluxOcr.complete(request.id,JSON.stringify(await recognize(request))); }
  catch(error) { RyluxOcr.complete(request.id,JSON.stringify({error:String(error.message||error)})); }
};
RyluxOcr.ready();
