// Lanczos3 / 14-bit separable convolution matching Chromium skia/ext's
// ImageOperations RESIZE_BEST used by Electron nativeImage.resize.
// Algorithm reference: chromium/src/skia/ext/image_operations.cc (BSD-3-Clause).
function coraflowResize(source, width, height) {
  const f=Math.fround, clamp=v=>Math.max(0,Math.min(255,v));
  function filters(src,dst) {
    const scale=f(dst/src), bounded=Math.min(1,scale), support=f(3/bounded), inverse=f(1/scale), result=[];
    for(let d=0;d<dst;d++) {
      const center=f(f(d+.5)*inverse),begin=Math.max(0,Math.floor(f(center-support))),end=Math.min(src-1,Math.ceil(f(center+support)));
      let sum=0;const values=[];
      for(let x=begin;x<=end;x++) {
        const distance=f(f(f(x+.5)-center)*bounded);
        let value;
        if(distance<=-3||distance>=3)value=0;
        else if(Math.abs(distance)<1.1920928955078125e-7)value=1;
        else {const pi=f(distance*f(Math.PI)), third=f(pi/3);value=f(f(f(Math.sin(pi))/pi)*f(f(Math.sin(third))/third));}
        values.push(value);sum=f(sum+value);
      }
      const fixed=values.map(v=>Math.trunc(f(f(v/sum)*16384)));
      fixed[Math.floor(fixed.length/2)]+=16384-fixed.reduce((a,b)=>a+b,0);
      result.push({begin,values:fixed});
    }
    return result;
  }
  const xfilters=filters(source.width,width),yfilters=filters(source.height,height);
  const horizontal=new Uint8ClampedArray(width*source.height*4), out=new ImageData(width,height);
  for(let y=0;y<source.height;y++)for(let x=0;x<width;x++)for(let c=0;c<4;c++) {
    const filter=xfilters[x];let sum=0;
    for(let k=0;k<filter.values.length;k++)sum+=filter.values[k]*source.data[(y*source.width+filter.begin+k)*4+c];
    horizontal[(y*width+x)*4+c]=clamp(sum>>14);
  }
  for(let y=0;y<height;y++)for(let x=0;x<width;x++)for(let c=0;c<4;c++) {
    const filter=yfilters[y];let sum=0;
    for(let k=0;k<filter.values.length;k++)sum+=filter.values[k]*horizontal[((filter.begin+k)*width+x)*4+c];
    out.data[(y*width+x)*4+c]=clamp(sum>>14);
  }
  return out;
}
