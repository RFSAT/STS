const d = require('docx');
const {Document,Packer,Paragraph,TextRun,HeadingLevel,AlignmentType,Table,TableRow,TableCell,
       WidthType,ShadingType,BorderStyle,PageBreak,LevelFormat,Header,Footer,PageNumber}=d;
const ACCENT="1F3864",RULE="BFBFBF",HEAD="D9E2F3",ZEBRA="F2F5FB";
const P=(t,o={})=>new Paragraph({alignment:o.align||AlignmentType.JUSTIFIED,
  spacing:{after:o.after===undefined?120:o.after,line:276},
  children:[new TextRun({text:t,size:o.size||21,bold:o.bold,italics:o.italics,font:"Calibri"})]});
const H1=t=>new Paragraph({heading:HeadingLevel.HEADING_1,spacing:{before:320,after:160},
  children:[new TextRun({text:t,size:30,bold:true,color:ACCENT,font:"Calibri"})]});
const H2=t=>new Paragraph({heading:HeadingLevel.HEADING_2,spacing:{before:240,after:100},
  children:[new TextRun({text:t,size:24,bold:true,color:ACCENT,font:"Calibri"})]});
const H3=t=>new Paragraph({heading:HeadingLevel.HEADING_3,spacing:{before:180,after:80},
  children:[new TextRun({text:t,size:21,bold:true,color:"2E5496",font:"Calibri"})]});
const Bullet=t=>new Paragraph({numbering:{reference:"bul",level:0},spacing:{after:70,line:276},
  alignment:AlignmentType.JUSTIFIED,children:[new TextRun({text:t,size:21,font:"Calibri"})]});
const Step=(t,i=0)=>new Paragraph({numbering:{reference:"num",level:0,instance:i},
  spacing:{after:80,line:276},alignment:AlignmentType.JUSTIFIED,
  children:[new TextRun({text:t,size:21,font:"Calibri"})]});
function table(cols,rows,widths){
  const total=widths.reduce((a,b)=>a+b,0);
  const cell=(txt,o={})=>new TableCell({width:{size:o.w,type:WidthType.DXA},
    shading:{type:ShadingType.CLEAR,fill:o.fill||"FFFFFF",color:"auto"},
    margins:{top:80,bottom:80,left:110,right:110},
    children:[new Paragraph({spacing:{after:0,line:252},alignment:AlignmentType.LEFT,
      children:[new TextRun({text:txt,size:19,bold:o.bold,font:"Calibri"})]})]});
  return new Table({width:{size:total,type:WidthType.DXA},columnWidths:widths,
    borders:{top:{style:BorderStyle.SINGLE,size:4,color:RULE},bottom:{style:BorderStyle.SINGLE,size:4,color:RULE},
      left:{style:BorderStyle.SINGLE,size:4,color:RULE},right:{style:BorderStyle.SINGLE,size:4,color:RULE},
      insideHorizontal:{style:BorderStyle.SINGLE,size:2,color:RULE},
      insideVertical:{style:BorderStyle.SINGLE,size:2,color:RULE}},
    rows:[new TableRow({tableHeader:true,children:cols.map((c,i)=>cell(c,{w:widths[i],bold:true,fill:HEAD}))}),
      ...rows.map((r,ri)=>new TableRow({children:r.map((c,i)=>cell(c,{w:widths[i],fill:ri%2?ZEBRA:"FFFFFF"}))}))]});
}
const Spacer=(h=100)=>new Paragraph({spacing:{after:h},children:[]});
function callout(title,body){
  return new Table({width:{size:9360,type:WidthType.DXA},columnWidths:[9360],
    borders:{top:{style:BorderStyle.SINGLE,size:4,color:ACCENT},bottom:{style:BorderStyle.SINGLE,size:4,color:ACCENT},
      left:{style:BorderStyle.SINGLE,size:18,color:ACCENT},right:{style:BorderStyle.SINGLE,size:4,color:ACCENT},
      insideHorizontal:{style:BorderStyle.NONE},insideVertical:{style:BorderStyle.NONE}},
    rows:[new TableRow({children:[new TableCell({width:{size:9360,type:WidthType.DXA},
      shading:{type:ShadingType.CLEAR,fill:"EDF1F9",color:"auto"},
      margins:{top:140,bottom:140,left:180,right:160},
      children:[new Paragraph({spacing:{after:60},children:[new TextRun({text:title,size:21,bold:true,color:ACCENT,font:"Calibri"})]}),
        ...body.map(b=>new Paragraph({spacing:{after:60,line:276},alignment:AlignmentType.JUSTIFIED,
          children:[new TextRun({text:b,size:20,font:"Calibri"})]}))]})]})]});
}
module.exports={d,P,H1,H2,H3,Bullet,Step,table,Spacer,callout,ACCENT,Document,Packer,Paragraph,
  TextRun,AlignmentType,PageBreak,LevelFormat,Header,Footer,PageNumber};
