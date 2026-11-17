// Run with CoraFlow's Electron executable; invoke the real desktop IPC handler.
const { app, nativeImage } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire}=require('node:module');
const desktopRoot='H:/APP/CoraFlow';
const desktopRequire=createRequire(desktopRoot+'/package.json');
const {createWorker,OEM,PSM}=desktopRequire('tesseract.js');
const {workerLanguage}=desktopRequire('./electron/ocr-language.cjs');
const root=path.resolve(__dirname,'../work/ocr-parity-fixtures');
app.whenReady().then(async()=>{
  let worker,lang,handler;
  const source=fs.readFileSync(desktopRoot+'/electron/main.cjs','utf8');
  const start=source.indexOf("ipcMain.handle('ocr:recognize'");
  const end=source.indexOf('\nipcMain.handle(',start+10);
  const context={nativeImage,Buffer,PSM,Number,Math,Uint8Array,Array,console,activeOcrRequest:null,
    OCR_LANGUAGES:new Set(['eng','chi_sim','chi_sim+eng','vie+eng']),workerLanguage,
    enqueueOcrTask:fn=>fn(),ocrErrorMessage:e=>String(e.message||e),
    ipcMain:{handle:(name,fn)=>{handler=fn;}},
    getOcrWorker:async language=>{
      if(worker&&lang!==language){await worker.terminate();worker=null;}
      if(!worker){worker=await createWorker(language.split('+'),OEM.LSTM_ONLY,{langPath:path.join(app.getPath('appData'),'Electron/ocr-cache'),cachePath:path.join(app.getPath('appData'),'Electron/ocr-cache'),gzip:false});lang=language;}
      return worker;
    }};
  try{
    vm.runInNewContext(source.slice(start,end),context);
    const cases=JSON.parse(fs.readFileSync(root+'/cases.json','utf8')),results=[];
    for(const c of cases){
      const img=nativeImage.createFromPath(root+'/'+c.file),size=img.getSize();
      const result=await handler({sender:{isDestroyed:()=>false,send:()=>{}}},{requestId:c.id,imageDataUrl:img.toDataURL(),language:c.language,kind:c.kind,scale:c.scale,region:{left:0,top:0,right:size.width,bottom:size.height}});
      results.push({id:c.id,...result});console.log(c.id,JSON.stringify(result));
    }
    fs.writeFileSync(root+'/desktop-results.json',JSON.stringify(results,null,2));
    if(worker)await worker.terminate();app.exit(0);
  }catch(error){console.error(error);if(worker)await worker.terminate();app.exit(1);}
});
