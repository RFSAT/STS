const B=require('./build.js');
const {d,Document,Packer,Paragraph,TextRun,AlignmentType,LevelFormat,Header,Footer,PageNumber}=B;
const fs=require('fs');
const body=[].concat(require('./part1.js'),require('./part2.js'),
                     require('./part3.js'),require('./part4.js'));
const doc=new Document({
  creator:"Dr Artur Krukowski", title:"STS Shooting Target Scorer — User Guide",
  numbering:{config:[
    {reference:"bul",levels:[{level:0,format:LevelFormat.BULLET,text:"•",alignment:AlignmentType.LEFT,
      style:{paragraph:{indent:{left:400,hanging:220}}}}]},
    {reference:"num",levels:[{level:0,format:LevelFormat.DECIMAL,text:"%1.",alignment:AlignmentType.LEFT,
      style:{paragraph:{indent:{left:400,hanging:260}}}}]}]},
  styles:{default:{document:{run:{font:"Calibri",size:21}}}},
  sections:[{properties:{page:{margin:{top:1100,bottom:1100,left:1040,right:1040}}},
    headers:{default:new Header({children:[new Paragraph({alignment:AlignmentType.RIGHT,
      border:{bottom:{style:d.BorderStyle.SINGLE,size:4,color:"BFBFBF",space:6}},
      children:[new TextRun({text:"STS Shooting Target Scorer — User Guide",size:17,color:"7F7F7F",font:"Calibri"})]})]})},
    footers:{default:new Footer({children:[new Paragraph({alignment:AlignmentType.CENTER,
      children:[new TextRun({children:[PageNumber.CURRENT],size:17,color:"7F7F7F",font:"Calibri"})]})]})},
    children:body}]
});
Packer.toBuffer(doc).then(b=>{fs.writeFileSync("/tmp/guide/STS-User-Guide_v1.48.2.docx",b);
  console.log("written",b.length,"bytes");});
