const {app,nativeImage}=require('electron');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
app.whenReady().then(()=>{
  try {
    const context={Math,Uint8ClampedArray,ImageData:class{constructor(width,height){this.width=width;this.height=height;this.data=new Uint8ClampedArray(width*height*4);}}};
    vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/coraflow-ocr/resize.js'),'utf8'),context);
    let comparisons=0,seed=41;
    for(const [width,height] of [[2,3],[17,11],[32,48],[71,29]])for(const scale of [2,3,4]){
      const source=new context.ImageData(width,height),bgra=Buffer.alloc(width*height*4);
      for(let i=0;i<width*height;i++){
        for(let c=0;c<3;c++){seed=(seed*1664525+1013904223)>>>0;source.data[i*4+c]=seed>>>24;}
        source.data[i*4+3]=255;
        bgra[i*4]=source.data[i*4+2];bgra[i*4+1]=source.data[i*4+1];bgra[i*4+2]=source.data[i*4];bgra[i*4+3]=255;
      }
      const expected=nativeImage.createFromBitmap(bgra,{width,height,scaleFactor:1}).resize({width:width*scale,height:height*scale,quality:'best'}).toBitmap();
      const actual=context.coraflowResize(source,width*scale,height*scale).data;
      for(let i=0;i<expected.length;i+=4)for(let c=0;c<3;c++)if(expected[i+c]!==actual[i+2-c])throw Error(`${width}x${height} scale ${scale} pixel ${i/4} channel ${c}: ${expected[i+c]} != ${actual[i+2-c]}`);
      comparisons++;
    }
    console.log(`PASS: ${comparisons} random opaque-image resizes match Electron pixel-for-pixel`);app.exit(0);
  }catch(error){console.error(error);app.exit(1);}
});
