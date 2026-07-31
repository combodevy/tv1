
$("#jiemudan dl").each(function(i){
    var hrefl=$(this).find("dt a").attr("href");
    var hrefurl = hrefl.split("/")[4].indexOf("cctv");
    var href_new = hrefl.split("/")[4];
    if(!hrefl){
        return;
    }else{
		if(new_test.indexOf(href_new)>-1){
			if(href_new.indexOf("cctv5plus")){
				$(this).addClass("active").siblings().removeClass("active");
			}else{
				$(this).addClass("active").siblings().removeClass("active");
			}
		}
    }
})
var acthtml='<ul class="program_list" id="jiemu"></ul><p class="complete"><a target="_blank" href="//tv.cctv.com/epg/">æ¥çå®æ´èç®å</a></p>';
$("#jiemudan .active").find("dd").html(acthtml)
//åå§ç¶æ
//ç¨æ·id
var authorid = getCookie("userSeqId");
//æµç§°
var author = "";
var barrageApp="cms_tvlm";
var guanggao=false;//å¹¿å
getCookie(authorid)
$("#noBarrage").hide()
/*å¼¹å¹*/
//window.shareObj={}
//æ¥æ¶åæ°å¼å§
function GetRequest() {
    var url = location.search; //è·åurlä¸­"?"ç¬¦åçå­ä¸²
    var theRequest = new Object();
    if (url.indexOf("?") != -1) {
        var str = url.substr(1);//å»é¤é®å· é®å·ä¸æ ä¸º0
        if (str.indexOf("&") != -1) {
            strs = str.split("&");
            for (var i = 0; i < strs.length; i++) {
                theRequest[strs[i].split("=")[0]] = decodeURIComponent(strs[i].split("=")[1]);
            }
        } else {
            theRequest[str.split("=")[0]] = decodeURIComponent(str.split("=")[1]);
        }
    }
    return theRequest;
}
var Requestnew = GetRequest();
var sttime = Requestnew['stime'];
//æ¥æ¶åæ°ç»æ
//function flashIsCreated(){//éæ³¨æ  æ­æ¾å¨åå»ºå®æåè°ç¨

//}
//æ­æ¾å¨
var _player_width = 940;
var _player_height = 529;
var isHttps = "true";
//var isHttps = location.href.split("/")[0].indexOf("https")>0 ? "true" : "false";
var playerParas = {
    divId: "player",   /*æ­æ¾å¨å®¹å¨idï¼å¿å¡«é¡¹*/
    w: _player_width,   /*æ­æ¾å¨å®½åº¦ï¼å¿å¡«é¡¹*/
    h: _player_height,   /*æ­æ¾å¨é«åº¦ï¼å¿å¡«é¡¹*/
    t: zhiboliu,   /*å°åç§°ï¼æ¯å¦cctv1,cctv13ç­ï¼å¿å¡«é¡¹*/
    st: "",   /*åçææ¶ç§»çå¼å§æ¶é´ï¼ä¹å¯ä»¥ç¨ç§è¡¨ç¤ºç10ä½æ¶é´æ³*/
    et: "",  /*åçææ¶ç§»çç»ææ¶é´.å¦ææ¯æ¶ç§»ï¼è¯¥åæ°å¯ä»¥ä¸ºç©º*/
    isAutoPlay: "true",   /*æ¯å¦èªå¨æ­æ¾ï¼åªæfalseä¸ºä¸èªå¨æ­æ¾ï¼å¶å®å¼ä¸ºèªå¨æ­æ¾*/
    ruleVisible: "true",   /*æ¯å¦æ¾ç¤ºæ¶ç§»å°ºå­ï¼é»è®¤trueè¡¨ç¤ºæ¾ç¤º*/
    br: "",  /*è®¾ç½®é»è®¤ç ç*/
    posterImg: "",   /*æ­æ¾å¨åè´´å¾ç*/
    isLive4k: "false",  /*æ¯å¦ä¸º4kæ­æ¾å¨ï¼trueæ¯4k,falseä¸æ¯*/
    isHttps: isHttps,  /*æ¯å¦httpsè§é¢ï¼trueæ¯,falseä¸æ¯*/
    wmode: "opaque",   /*flashæ­æ¾å¨ççªå£æ¨¡å¼ï¼é»è®¤ä¸ºopaque*/
    hasBarrage: "false",  /*æ¯å¦æå¼¹å¹åè½ï¼é»è®¤falseï¼falseæ¶ä¸æ¾ç¤ºå¼¹å¹ãä¸æ¾ç¤ºå¼¹å¹è®¾ç½®æé®ãä¸æ¾ç¤ºå¼¹å¹å¼å³ãä¸è®¿é®å¼¹å¹æ¥å£åè¡¨æåéç½®æ¥å£e*/
    playerType : "live",   /*æ­æ¾å¨ç±»åï¼smallè¡¨ç¤ºå°çªæ­æ¾å¨*/
    webFullScreenOn: "true",   /*æ¯å¦æ¾ç¤ºç½é¡µå¨å±æé®ï¼é»è®¤trueè¡¨ç¤ºæ¾ç¤º*/
    isLeftBottom: "false",  /*æ­æ¾æé®æ¯å¦å¨æ­æ¾å¨å·¦ä¸è§,ä¸ºtrueè¡¨ç¤ºæ¯ï¼falseè¡¨ç¤ºæ­æ¾æé®å¨æ­æ¾å¨ä¸­é´*/
    language: "",  /*è¯­è¨ï¼é»è®¤ä¸­æï¼enè¡¨ç¤ºè±è¯­*/
    others: ""   /*å¶å®å¾å®åæ°*/
};
// createLivePlayer(playerParas);
if(sttime=='' || sttime==undefined || sttime==null){
    //createLivePlayer(playerParas);
}else{
    playerParas.st = sttime;
    //document.getElementById("flashplayer_player").PageCallFlash({"IsLive":"false","ShiftTime":sttime});
}
createLivePlayer(playerParas);
// æ­æ¾å¨æ¹æ³è°ç¨
function flashToNormalWindow(){   //ç¨æ·ç¹å» åæ¶ç½é¡µå¨å±
    $(".jiemuguanwang18950_zhibo_ind01").css("z-index","11");
    $(".jiemuguanwang18950_zhibo_ind01 .playingVideo .video_right .scroll_list .scrollbar").css("z-index","0");
    $(".jiemuguanwang18950_zhibo_ind01 .playingVideo .video_right .scroll_list .viewport").css("z-index","0");

}

function flashToWebFullWindow(){  //ç¨æ·ç¹å» ç½é¡µå¨å±
    $(".jiemuguanwang18950_zhibo_ind01").css("z-index","200");
    $(".jiemuguanwang18950_zhibo_ind01 .playingVideo .video_right .scroll_list .scrollbar").css("z-index","-1");
    $(".jiemuguanwang18950_zhibo_ind01 .playingVideo .video_right .scroll_list .viewport").css("z-index","-1");

}
var op;
//function flashPlayerBackToLive(){   //ç¨æ·ç¹å»è¿åç´æ­
//			jiemudan();
//}
//function FlashADComplete(){  //æ£æµå¹¿åæ¯å¦å®æ
//	guanggao=true;
//}
Date.prototype.Format = function (fmt) { //author: meizz
    var o = {
        "M+": this.getMonth() + 1, //æä»½
        "d+": this.getDate(), //æ¥
        "h+": this.getHours(), //å°æ¶
        "m+": this.getMinutes(), //å
        "s+": this.getSeconds(), //ç§
        "q+": Math.floor((this.getMonth() + 3) / 3), //å­£åº¦
        "S": this.getMilliseconds() //æ¯«ç§
    };
    if (/(y+)/.test(fmt)) fmt = fmt.replace(RegExp.$1, (this.getFullYear() + "").substr(4 - RegExp.$1.length));
    for (var k in o)
        if (new RegExp("(" + k + ")").test(fmt)) fmt = fmt.replace(RegExp.$1, (RegExp.$1.length == 1) ? (o[k]) : (("00" + o[k]).substr(("" + o[k]).length)));
    return fmt;
}
/*èç®åè·å*/
var now1;
if(!now){
    now1=new Date();
}else{
    now1=now;
}
var getmonth = (now1.getMonth()+1<10?"0"+(now1.getMonth()+1):now1.getMonth()+1).toString();
var getdata = (now1.getDate()<10?"0"+now1.getDate():now1.getDate()).toString();
var gethours = now1.getHours()<10?"0"+now1.getHours():now1.getHours();
var getminutes = now1.getMinutes()<10?"0"+now1.getMinutes():now1.getMinutes();
var getseconds = now1.getSeconds()<10?"0"+now1.getSeconds():now1.getSeconds();
now1 = now1.getFullYear()+getmonth+getdata;
new_sttime = Requestnew['stime'];
var stmm=true;
if(new_sttime == '' || new_sttime == undefined){
    new_sttime = now1;
}else{
    nnew_sttime = new Date(parseInt(new_sttime+"000")).Format('yyyyMMddhhmm');
    new_sttime = nnew_sttime.substr(0,8);
}
var siui="";//ç°å¨çæ¶é´åç´æ­ä¸­çç»ææ¶é´å·®
jiemudan()
function jiemudan(){
    if(!stmm){
        new_sttime=now.getFullYear()+(now.getMonth()+1<10?"0"+(now.getMonth()+1):now.getMonth()+1).toString()+(now.getDate()<10?"0"+now.getDate():now.getDate()).toString();
    }
    $.ajax({
        type: "get",
        url: "//api.cntv.cn/epg/getEpgInfoByChannelNew?c="+zhiboliu+"&serviceId=tvcctv&d="+new_sttime+"&cb=?",
        dataType: "jsonp",
        jsonp:"cb",
        jsonpCallback:'t',
        cache:true,
        success: function (data) {
            stmm=false;
            var data=data.data;
            for(var key in data){
                var programArray = data[key].list;
            }
            var nowData = Date.parse(now)/1000;
            var allvideo = programArray;
            var liHtml ='';
            var j="null";
            for (var i=0;i<allvideo.length;i++) {
                var startTime = allvideo[i].startTime;
                var endTime = allvideo[i].endTime;
                if (nowData >= startTime && nowData < endTime) {

                    j=i;
                }
            };
            if(j=="null"){
                var kikk=now.getTime()/1000;
                if(allvideo[allvideo.length-1].endTime<=kikk){
                    for(var ki=5;ki>0;ki--){
                        liHtml += '<li class="list"><span>' + allvideo[allvideo.length-ki].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[allvideo.length-ki].title + '</a><a title="' + allvideo[allvideo.length-ki].title + '" starttime="' + allvideo[allvideo.length-ki].startTime + '" endtime="' + allvideo[allvideo.length-ki].endTime + '" href="javascript:;" class="btn">åç</a></li>';
                    }
                }else{
                    for(var ki=0;ki<5;ki++){
                        liHtml += '<li class="list"><span>' + allvideo[ki].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[ki].title + '</a></li>';
                    }
                }
            }else if(j<2&&j>=0){

                for(n=0;n<=j-1;n++){
                    liHtml += '<li class="list"><span>' + allvideo[n].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[n].title + '</a><a title="' + allvideo[n].title + '" starttime="' + allvideo[n].startTime + '" endtime="' + allvideo[n].endTime + '" href="javascript:;" class="btn">åç</a></li>';
                }
                liHtml += '<li class="cur act"><span>' + allvideo[j].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[j].title + '</a><a title="' + allvideo[j].title + '" starttime="' + allvideo[j].startTime + '" endtime="' + allvideo[j].endTime + '" href="javascript:;" class="btn">ç´æ­ä¸­</a></li>';
                for(m=j+1;m<5;m++){
                    liHtml += '<li class=""><span>' + allvideo[m].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[m].title + '</a><a title="' + allvideo[m].title + '" starttime="' + allvideo[m].startTime + '" endtime="' + allvideo[m].endTime + '" href="javascript:;" class="btn"></a></li>';
                }
            }else if(j<allvideo.length&&j>=allvideo.length-2){
                if(j==allvideo.length-1){
                    for(n=j-4;n<=j-1;n++){
                        liHtml += '<li class="list"><span>' + allvideo[n].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[n].title + '</a><a title="' + allvideo[n].title + '" starttime="' + allvideo[n].startTime + '" endtime="' + allvideo[n].endTime + '" href="javascript:;" class="btn">åç</a></li>';
                    }
                }else if(j==allvideo.length-2){
                    for(n=j-3;n<=j-1;n++){
                        liHtml += '<li class="list"><span>' + allvideo[n].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[n].title + '</a><a title="' + allvideo[n].title + '" starttime="' + allvideo[n].startTime + '" endtime="' + allvideo[n].endTime + '" href="javascript:;" class="btn">åç</a></li>';
                    }
                }

                liHtml += '<li class="cur act"><span>' + allvideo[j].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[j].title + '</a><a title="' + allvideo[j].title + '" starttime="' + allvideo[j].startTime + '" endtime="' + allvideo[j].endTime + '" href="javascript:;" class="btn">ç´æ­ä¸­</a></li>';
                for(m=j+1;m<allvideo.length;m++){
                    liHtml += '<li class=""><span>' + allvideo[m].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[m].title + '</a><a title="' + allvideo[m].title + '" starttime="' + allvideo[m].startTime + '" endtime="' + allvideo[m].endTime + '" href="javascript:;" class="btn"></a></li>';
                }
            }else{

                for(n=j-2;n<=j-1;n++){
                    liHtml += '<li class="list"><span>' + allvideo[n].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[n].title + '</a><a title="' + allvideo[n].title + '" starttime="' + allvideo[n].startTime + '" endtime="' + allvideo[n].endTime + '" href="javascript:;" class="btn">åç</a></li>';
                }
                liHtml += '<li class="cur act"><span>' + allvideo[j].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[j].title + '</a><a title="' + allvideo[j].title + '" starttime="' + allvideo[j].startTime + '" endtime="' + allvideo[j].endTime + '" href="javascript:;" class="btn">ç´æ­ä¸­</a></li>';
                for(m=j+1;m<=j+2;m++){
                    liHtml += '<li class=""><span>' + allvideo[m].showTime + '</span><a href="javascript:;" class="txt">' + allvideo[m].title + '</a><a title="' + allvideo[m].title + '" starttime="' + allvideo[m].startTime + '" endtime="' + allvideo[m].endTime + '" href="javascript:;" class="btn"></a></li>';
                }
            }
            $("#jiemu").html(liHtml);
            $('#scrollbar').tinyscrollbar();
            var lenli=$("#jiemu li").length;
            $("#jiemu li").each(function(i){
                $("#jiemu li").eq(0).addClass("first").siblings().removeClass("first");
                $("#jiemu li").eq(lenli-1).addClass("last").siblings().removeClass("last");
            })
            /*ç¹å»æ­æ¾åç*/
            $("#jiemu li.list").each(function(){
                var look_self = $(this);
                $(this).find("a.btn").click(function(){
                    playerParas['ruleVisible']=true;
                    var new_starttime =look_self.find("a.btn").attr("starttime");
                    //var new_endtime = look_self.find("a.btn").attr("endtime");
                    //var new_title = look_self.find("a.btn").attr("title");

                    playerParas.st = new_starttime;
                    createLivePlayer(playerParas);

                    //document.getElementById("flashplayer_player").PageCallFlash({"IsLive":"false","ShiftTime":new_starttime});

                })
            })
            /*ç¹å»æ­æ¾ç´æ­*/



            $("#jiemu li.act").find("a.btn").click(function(){
                var _this = $("#jiemu li.act");
                $("#player").html("");
                //settime=""
                setTimeout(function(){
                    playerParas.st = "";
                    createLivePlayer(playerParas);
                },1000)
            })
            $("#jiemudan dl").each(function(i){
                var index=$("#jiemudan dl.active").index();

                var $scrollbar6 = $('#scrollbar');
                if(index>=7 && index <=10){
                    $scrollbar6.tinyscrollbar();
                    var scrollbar6 = $scrollbar6.data("plugin_tinyscrollbar");
                    scrollbar6.update(242);
                }else if(index>10 && index <15){
                    $scrollbar6.tinyscrollbar();
                    var scrollbar6 = $scrollbar6.data("plugin_tinyscrollbar");
                    scrollbar6.update(435);
                }else if(index >=15){
                    $scrollbar6.tinyscrollbar();
                    var scrollbar6 = $scrollbar6.data("plugin_tinyscrollbar");
                    scrollbar6.update(575);

                }
            })

            if(j=="null"){
                siui=1000*60*5;
            }else{
                var setchangetime=allvideo[j].endTime;
                siui=setchangetime*1000- now.getTime();
            }
            now=new Date(now.getTime()+siui);
            op=setTimeout(function(){
                jiemudan();
            },siui);
        },
        error: function () {

        }
    });
}

/*çè¨è·å+å¼¹å¹*/
//è·åçè¯è®ºè¡¨æå­ç¬¦æ¿æ¢ä¸ºå¾ç
function biaoqingtihuan(str){
    var arrbiaoqing = [
        '<img data=[/å¤§ç¬] title=å¤§ç¬ alt=å¤§ç¬ width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007613904_574.png>',

        '<img data=[/ç¹èµ] title=ç¹èµ alt=ç¹èµ width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007607546_99.png>',

        '<img data=[/æè®¶] title=æè®¶ alt=æè®¶ width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007601555_659.png>',

        '<img data=[/é¼æ] title=é¼æ alt=é¼æ width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007594354_432.png>',

        '<img data=[/çº¢å¿] title=çº¢å¿ alt=çº¢å¿ width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007578390_332.png>',

        '<img data=[/ç¥ç¦] title=ç¥ç¦ alt=ç¥ç¦ width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007571244_604.png>',

        '<img data=[/æµæ±] title=æµæ± alt=æµæ± width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007563634_197.png>',

        '<img data=[/é²è±] title=é²è± alt=é²è± width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007544779_736.png>',

        '<img data=[/èå©] title=èå© alt=èå© width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007536417_174.png>',

        '<img data=[/å æ²¹] title=å æ²¹ alt=å æ²¹ width="49" height="49" src=//p1.img.cctvpic.com/photoAlbum/page/performance/img/2018/8/23/1535007525274_997.png>'

    ];

    var biaoqing="";
    var strbiaoqing = str.replace(/\[\/([\u4e00-\u9fa5]+)\]/g, function(item, index){

        switch(index){

            case "å¤§ç¬":
                return arrbiaoqing[0]
                break;

            case "ç¹èµ":
                return arrbiaoqing[1]
                break;

            case "æè®¶":
                return arrbiaoqing[2]
                break;

            case "é¼æ":
                return arrbiaoqing[3]
                break;

            case "çº¢å¿":
                return arrbiaoqing[4]
                break;

            case "ç¥ç¦":
                return arrbiaoqing[5]
                break;

            case "æµæ±":
                return arrbiaoqing[6]
                break;

            case "é²è±":
                return arrbiaoqing[7]
                break;

            case "èå©":
                return arrbiaoqing[8]
                break;

            case "å æ²¹":
                return arrbiaoqing[9]
                break;

            default :
                return index

        }
    });
    return strbiaoqing;
}

//getdanmu();
function getdanmu(){
    var barrage_list = [];
    var barrage_list1 = [];
    var url1 = "//common.newcomment.cntv.cn/comment/list/app/cms_tvlm/itemid/"+itemid+"?itemtype=0&prepage=100&nature=1&jsonp_callback=?";
    $.ajax({
        type: "get",
        url: url1,
        dataType: "jsonp",
        jsonp: "callback",
        jsonpCallback:itemid,
        cache:true,
        async:false,
        success: function (data) {
            var userSeqId=getCookie1('userSeqId');
            for (var i=0;i<data.data.content.length;i++ ){
                barrage_list.push(biaoqingtihuan(data.data.content[i].message))
                barrage_list1.push(biaoqingtihuan(data.data.content[i].authorid))
            }
            var page = 0;
            var page_top = 0;
            var random_id;
            var interval = setInterval(function(){
                barrageBarrage();
            },6000);
            barrageBarrage();
            function barrageBarrage(){
                var userSeqId=getCookie1('userSeqId');
                if(barrage_list[page] != undefined){
                    var message_le=barrage_list[page].replace(/(\<img).*?(\/\>)/g,"").length;
                    var img_le=barrage_list[page].split("<img").length-1;
                    if(img_le>0){
                        message_le=img_le*5
                    }
                    if(message_le > 20){
                        var random_id01 = parseInt(Math.random()*3,10)+1;
                        var random_id02 = parseInt(Math.random()*3,10)+1;
                        if(random_id == random_id02){
                            if(random_id01==1){
                                random_id = 2;
                            }else if(random_id==2){
                                random_id=3;

                            }else{
                                random_id=1
                            }

                        }else{
                            random_id = random_id02
                        }

                        if(barrage_list1[page]!=undefined&&barrage_list1[page]==userSeqId){
                            $("#barrage .con").append('<div class="cur01 top01" style="color:#3db2e4"><span>'+barrage_list[page]+'</span></div>');
                        }else{
                            $("#barrage .con").append('<div class="cur01 top01"><span>'+barrage_list[page]+'</span></div>');
                        }

                    }else if(message_le<=20 && message_le>15 ){
                        var random_id01 = parseInt(Math.random()*3,10)+1;
                        var random_id02 = parseInt(Math.random()*3,10)+1;
                        if(random_id == random_id02){

                            if(random_id01==1){
                                random_id = 2;
                            }else if(random_id==2){
                                random_id=3;

                            }else{
                                random_id=1
                            }

                        }else{
                            random_id = random_id02
                        }
                        if(barrage_list1[page]!=undefined&&barrage_list1[page]==userSeqId){
                            $("#barrage .con").append('<div class="cur0'+random_id+' top01"  style="color:#3db2e4"><span>'+barrage_list[page]+'</span></div>');
                        }else{
                            $("#barrage .con").append('<div class="cur0'+random_id+' top01"><span>'+barrage_list[page]+'</span></div>');
                        }
                    }else{
                        page_top++
                        if(barrage_list[page] != undefined){
                            if(barrage_list1[page]==userSeqId){
                                $("#barrage .con").append('<div class="cur0'+page_top+' top01" style="color:#3db2e4"><span>'+barrage_list[page]+'</span></div>');
                            }else{
                                $("#barrage .con").append('<div class="cur0'+page_top+' top01"><span>'+barrage_list[page]+'</span></div>');
                            }
                        }
                    }
                    if(page_top == 3){
                        page_top=0;
                    }
                }
                page ++
                if(barrage_list[page] != undefined){
                    var message_le=barrage_list[page].replace(/(\<img).*?(\/\>)/g,"").length;
                    var img_le=barrage_list[page].split("<img").length-1;
                    if(img_le>0){
                        message_le=img_le*5
                    }
                    if(message_le > 20){
                        var random_id01 = parseInt(Math.random()*3,10)+1;
                        var random_id02 = parseInt(Math.random()*3,10)+1;
                        if(random_id == random_id02){

                            if(random_id01==1){
                                random_id = 2;
                            }else if(random_id==2){
                                random_id=3;

                            }else{
                                random_id=1
                            }

                        }else{
                            random_id = random_id02
                        }

                        if(barrage_list1[page]!=undefined&&barrage_list1[page]==userSeqId){
                            $("#barrage .con").append('<div class="cur01 top02" style="color:#3db2e4"><span>'+barrage_list[page]+'</span></div>');
                        }else{
                            $("#barrage .con").append('<div class="cur01 top02"><span>'+barrage_list[page]+'</span></div>');
                        }

                    }else if(message_le<=20 && message_le>15 ){
                        var random_id01 = parseInt(Math.random()*3,10)+1;
                        var random_id02 = parseInt(Math.random()*3,10)+1;
                        if(random_id == random_id02){

                            if(random_id01==1){
                                random_id = 2;
                            }else if(random_id==2){
                                random_id=3;

                            }else{
                                random_id=1
                            }

                        }else{
                            random_id = random_id02

                        }

                        if(barrage_list1[page]!=undefined&&barrage_list1[page]==userSeqId){
                            $("#barrage .con").append('<div class="cur0'+random_id+' top02"  style="color:#3db2e4"><span>'+barrage_list[page]+'</span></div>');
                        }else{
                            $("#barrage .con").append('<div class="cur0'+random_id+' top02"><span>'+barrage_list[page]+'</span></div>');
                        }


                    }else{
                        page_top++
                        if(barrage_list[page] != undefined){

                            if(barrage_list1[page]==userSeqId){
                                $("#barrage .con").append('<div class="cur0'+page_top+' top02" style="color:#3db2e4"><span>'+barrage_list[page]+'</span></div>');
                            }else{
                                $("#barrage .con").append('<div class="cur0'+page_top+' top02"><span>'+barrage_list[page]+'</span></div>');
                            }

                        }
                    }
                    if(page_top == 3){
                        page_top=0;
                    }

                }
                page ++
                if(barrage_list[page] != undefined){
                    var message_le=barrage_list[page].replace(/(\<img).*?(\/\>)/g,"").length;
                    var img_le=barrage_list[page].split("<img").length-1;
                    if(img_le>0){
                        message_le=img_le*5
                    }
                    if(message_le > 20){
                        var random_id01 = parseInt(Math.random()*3,10)+1;
                        var random_id02 = parseInt(Math.random()*3,10)+1;
                        if(random_id == random_id02){

                            if(random_id01==1){
                                random_id = 2;
                            }else if(random_id==2){
                                random_id=3;

                            }else{
                                random_id=1
                            }

                        }else{
                            random_id = random_id02
                        }

                        if(barrage_list1[page]!=undefined&&barrage_list1[page]==userSeqId){
                            $("#barrage .con").append('<div class="cur01 top03" style="color:#3db2e4"><span>'+barrage_list[page]+'</span></div>');
                        }else{
                            $("#barrage .con").append('<div class="cur01 top03"><span>'+barrage_list[page]+'</span></div>');
                        }

                    }else if(message_le<=20 && message_le>15 ){
                        var random_id01 = parseInt(Math.random()*3,10)+1;
                        var random_id02 = parseInt(Math.random()*3,10)+1;
                        if(random_id == random_id02){

                            if(random_id01==1){
                                random_id = 2;
                            }else if(random_id==2){
                                random_id=3;

                            }else{
                                random_id=1
                            }

                        }else{
                            random_id = random_id02

                        }

                        if(barrage_list1[page]!=undefined&&barrage_list1[page]==userSeqId){
                            $("#barrage .con").append('<div class="cur0'+random_id+' top03"  style="color:#3db2e4"><span>'+barrage_list[page]+'</span></div>');
                        }else{
                            $("#barrage .con").append('<div class="cur0'+random_id+' top03"><span>'+barrage_list[page]+'</span></div>');
                        }


                    }else{
                        page_top++
                        if(barrage_list[page] != undefined){

                            if(barrage_list1[page]==userSeqId){
                                $("#barrage .con").append('<div class="cur0'+page_top+' top03" style="color:#3db2e4"><span>'+barrage_list[page]+'</span></div>');
                            }else{
                                $("#barrage .con").append('<div class="cur0'+page_top+' top03"><span>'+barrage_list[page]+'</span></div>');
                            }

                        }
                    }
                    if(page_top == 3){
                        page_top=0;
                    }

                }
                page ++
                $("#barrage .con .cur01").animate({"left":-385},8000,'linear');
                $("#barrage .con .cur02").animate({"left":-385},12000,'linear');
                $("#barrage .con .cur03").animate({"left":-385},12000,'linear');
                $("#barrage .con .cur01").animate({"left":-385},24000,'linear');
                $("#barrage .con .cur02").animate({"left":-385},20000,'linear');
                $("#barrage .con .cur03").animate({"left":-385},18000,'linear');
                if(page >= barrage_list.length-1){
                    clearInterval(interval);
                }
                $(".barrage .con div").each(function(index, element) {
                    if($(this).css("left") == "-385px"){
                        $(this).remove();
                    }
                });
            }
        },
        error: function (data) {
        }
    });
}

//è¡¨æ¸å
function getcontent(id){

    $("#"+id).find("td").click(function(){
        $("#tts").focus();
        var yansenzi = $(this).text();
        if(yansenzi==""){
            yansenzi = $(this).find("img").attr("data");
        }
        var areaval = $("#tts").val();
        $("#tts").val(areaval+yansenzi)
        areaval =  $("#tts")
    })

}
getcontent("biaoqing0")
getcontent("biaoqing1")
getcontent("biaoqing2")
getcontent("yanwenzi")
getcontent("wenzi")

var  danmuarry=["ï½(ï¿£â½ï¿£ï½)(ï½ï¿£â½ï¿£)ï½","o(*////â½////*)o","Î£ï¼ Â° â³ Â°|||ï¼ï¸´","d====(ï¿£â½ï¿£*)b","[]~(ï¿£â½ï¿£)~*","*(à©­*ËáµË)à©­*à¬","(..â¢Ë_Ëâ¢..)",">Â°))))å½¡","o(ï¿£ãï¿£oï¼)","(âªï½¡âª)ï½¡ï½¡ï½¡zzz","(lllï¿¢Ïï¿¢)","ã½(â¿ï¾â½ï¾)ã","ã¾(â§â½â¦*)o","(Â´â½`Êâ¡Æª)","(à¹â¢Ìãâ¢Ì)Ùâ§","(*ï¿£3ï¿£)â­","o(â¥ï¹â¥)o","â®(â¯â½â°)â­","(âï½â)","(â_â)","è¿å¤§æ¦æ¯é¦é²¤æ¬äººäº~","åå®¹å¼èµ·æåº¦èéï¼","ä½ æä¹è¿ä¹ä¼ç§ï¼","åè³ï¼ä½ æä¹çï¼","ç®ä¸ä¸å¾å¼å¿ï¼","åæ¡å¼¹å¹ååæ","éè¦çäºè¯´ä¸é","è¯·æ¶ä¸æçèç","åæå°±æ¯èå©ï¼","åå®³äºæçå½","æ»¡æ»¡çæ­£è½é","å æ²¹ï¼å æ²¹ï¼","æå¨ç¹èµï¼","è¡¨ç¤ºéæï¼","å¾å¥½å¾å¼ºå¤§","ææ³éé","çç²¾å½©ï¼","æ¯æä¸ä¸","åååå","å²é¸­~~~","ä½è°è·¯è¿","å¥½å¨åï¼","æcall","ç»åï¼","å¿èï¼","å®åæ´¾","é¡¶ä¸ä¸ª","æ¯å¿","ç¡¬æ ¸","V5","[/å¾®ç¬]","[/é·]","[/äº²äº²]","[/æ é¼»]","[/è¶]","[/é¼æ]","[/å®³ç¾]","[/çé®]","[/è°ç®]","[/ææ]","[/æè¸]","[/æ]","[/æºæº]","[/ææç¼]","[/å¤§ç¬]","[/åå]","[/æç]","[/å¼]","[/å·ç¬]","[/ç¡çäº]","[/æ¯å¿]","[/ç¹èµ]","[/éä½ è±è±]","[/ç¤¼ç©]","[/åºç¥]","[/666]","[/ææ°]","[/æ£æ£å]","[/æcall]","[/åå®³äº]","[/å æ²¹]","[/å¤§ç¥V5]","[/å¾®ç¬1]","[/æè¸1]","[/ææ1]","[/æºæº1]","[/å¥¸ç¬1]","[/å·ç¬1]","[/æç1]","[/çé®1]","[/è¶1]","[/æ é¼»1]","[/äº²äº²1]","[/é·1]","[/æ1]","[/ææç¼1]","[/åå1]","[/å¼1]","[/é¼æ1]","[/ç¡è§1]","[/å®³ç¾1]","[/è°ç®1]","[/å¤§ç¬2]","[/ç¹èµ2]","[/é¼æ2]","[/æè®¶2]","[/çº¢å¿2]","[/ç¥ç¦2]","[/ç®è±2]","[/æµæ±2]","[/èå©2]","[/å æ²¹2]"]

var mianshen=false
//å¼¹å¹åå£åæ°
var danmudata = {
    app:barrageApp,
    authorid:authorid,
    author:author,
    prepage:500
}
//æ°å¢
function pinglunf(){
    var jen = $.trim($("#tts").val())
    var jiance=jen
    jiance=escape(jiance)
    for(var i=0;i<danmuarry.length;i++){
        if(jiance.indexOf(escape(danmuarry[i]))>-1){
            var reg=new RegExp(escape(danmuarry[i]),"g")
            jiance=jiance.replace(reg,'')
        }
    }
    if(jiance.length==0){
        mianshen=true;
    }
    //åéå¼¹å¹å¯¹åºçè§é¢æ¶é´
    jen = encodeURIComponent(jen);
    if(typeof thisMovie == "function"){
        try{
            var relative_time = parseInt(thisMovie("flashplayer_player").getTimeInSeconds()*1000);//20171027æ°å¢
        }catch(e){
        }

    }else{
        var relative_time = 0;
    }
    var  itemiddanmu=guid;
    var dm_send_url = "//newcomment.cntv.cn/comment/post"
    if(mianshen){
        var danmurul=dm_send_url+"?app="+danmudata.app+"&itemid="+itemid+"&relative_time="+relative_time+"&common_words_id=67286&authorid="+danmudata.authorid+"&message="+jen+"&author="+danmudata.author+"&jsonp_callback=?";
    }else{
        var danmurul=dm_send_url+"?app="+danmudata.app+"&itemid="+itemiddanmu+"&relative_time="+relative_time+"&authorid="+danmudata.authorid+"&message="+jen+"&author="+danmudata.author+"&jsonp_callback=?";
    }

    $.ajax({
        type:"post",
        url:danmurul,
        async:true,
        //timeout:5000,
        dataType:'jsonp',
        jsonp:"jsonp_Callback",
        jsonpCallback:"callback_"+danmudata.app+"_"+itemiddanmu,
        cache:true,
        success:function(data,textStatus,jqXHR){
            if(textStatus != "success"){
                return
            }else{
                if(mianshen){
                    alert("å·²åéæå")
                    try{
                        thisMovie("flashplayer_player").commitBarrage(decodeURIComponent(jen))
                    }catch(e){
                    }

                }else{
                    alert("å·²æäº¤å®¡æ ¸")
                }
                setTimeout(function(){
                    getdanmu();
                },500)
            }


            $("#tts").val('');

        }
    });
}


function realName(){
    var userSeqId=getCookie('userSeqId');
    if (userSeqId == null){
        return;
    }else{
        $.ajax({
            type: "get",
            url: "//reg.cctv.com/authenicateAction/isAuth.action?formFlag=3&userSeqId="+userSeqId+"",
            dataType: "jsonp",
            jsonp: "callback",
            async:false,
            success: function (data) {
                if (data.isReal == 0){//ä¸æ¯
                    alert("æ ¹æ®æ³å¾æ³è§è¦æ±ï¼è¯·æ¨ç»å®ææºå·å®æåå°å®åè®¤è¯ååè¡¨è¯è®ºãæè°¢æ¨ççè§£åæ¯æï¼");
                    var jUrl = "//reg.cctv.com/authenicateAction/mobile/bind.html?service="+window.location.href;
                    var jUrl1 = jUrl.split("?")[2].split("&")[3].split("=")[1];
                    window.parent.location.href= "//reg.cctv.com/authenicateAction/mobile/bind.html?formFlag=3&userSeqId="+userSeqId+"&service="+jUrl1;
                    //window.open("http://reg.cctv.com/authenicateAction/mobile/bind.html?service="+window.location.href);
                }else{
                    pinglunf();
                    return;
                }
            },
            error: function (data) {
                alert("ajaxå¤±è´¥ï¼"+data.errtype)
            }
        });
    }
}
$(".video_btnBar .rightBar").on("click",".send",function(){
    //éªè¯æ¯å¦ç»å½ï¼æªç»å½å¼¹åºç»å½æ¡
    if(getCookie("userSeqId") == null || getCookie("userSeqId") == ""){
        $("#loginFloat").show();
        $("#username").focus();
        $("body,html").animate({scrollTop:0},300);
        return
    }
    realName();

})
//å¤å¶é¾æ¥
function SelectText(element) {
    var text = document.getElementById(element);
    if ($.browser.msie) {
        var range = document.body.createTextRange();
        range.moveToElementText(text);
        range.select();
    } else if ($.browser.mozilla || $.browser.opera) {
        var selection = window.getSelection();
        var range = document.createRange();
        range.selectNodeContents(text);
        selection.removeAllRanges();
        selection.addRange(range);
    } else if ($.browser.safari) {
        var selection = window.getSelection();
        selection.setBaseAndExtent(text, 0, text, 1);
    }
}
function cp(x)
{
    SelectText(x);
    document.execCommand("copy");
    alert('å¤å¶æå')
}
var timervideohtml = setInterval(function(){
    if ($("#flashplayer_player").length>100){
        var videohtml=$("#player").html();
        $("#videocopy").html(encodeURIComponent(videohtml));
        clearInterval(timervideohtml)
    }else{
        var videohtml=$("#player").html();
        $("#videocopy").html(encodeURIComponent(videohtml));
    }
},100)
$("#hrefcopy").html(window.location.href)
$("#copy_video").click(function(){
    cp('videocopy');
    //shareObj.shareUrl=decodeURIComponent($("#videocopy").html());
})
$("#copy_href").click(function(){
    cp('hrefcopy');
    //shareObj.shareUrl=window.location.href
})
function getCookie(name) {
    var arr, reg = new RegExp("(^| )" + name + "=([^;]*)(;|$)");
    if (arr = document.cookie.match(reg)) return unescape(arr[2]);
    else return null
}





//æ¾ç¤ºæµ®å±
function showBox(id,show,scroll){

    var timer = null;
    var oShowObj = $(id);
    var oShowObj1 = oShowObj.find(show);
    clearTimeout(timer);

    oShowObj.bind("mouseover",function(){
        clearTimeout(timer);
        oShowObj.addClass("changebg");
        oShowObj.find(show).addClass("cur");
        $('#'+scroll).tinyscrollbar();
    });
    oShowObj.bind("mouseout",function(){
        clearTimeout(timer);
        timer = setTimeout(function(){
            oShowObj.removeClass("changebg");
            oShowObj.find(show).removeClass("cur");
        },200)
    });
    oShowObj1.bind("mouseover",function(){
        $('#'+scroll).tinyscrollbar();
        clearTimeout(timer);
    });
    oShowObj1.bind("mouseout",function(){
        clearTimeout(timer);
        timer = setTimeout(function(){
            oShowObj.removeClass("changebg");
            oShowObj.find(show).removeClass("cur");
        },200)
    });
}
showBox(".wenzi",".wenzi_box","wenzi");
showBox(".yanwenzi",".yanwenzi_box","yanwenzi");
showBox(".biaoqing",".biaoqing_box","biaoqing0");

var biaoqing_tabs = $(".video_btnBar .rightBar .biaoqing .biaoqing_box .tabs span");
var biaoqing_cont = $(".video_btnBar .rightBar .biaoqing .biaoqing_box .tabs_con");
biaoqing_tabs.on("click",function(){
    oIndex = $(this).index();
    $(this).addClass("line").siblings().removeClass("line");
    biaoqing_cont.eq(oIndex).addClass("show").siblings(".tabs_con").removeClass("show");
    $('#biaoqing'+oIndex).tinyscrollbar();
})

if( !('placeholder' in document.createElement('input')) ){
    $('input[placeholder],textarea[placeholder]').each(function(){
        var that = $(this),
            text= that.attr('placeholder');
        if(that.val()===""){
            that.val(text).addClass('placeholder');
        }
        that.focus(function(){
                if(that.val()===text){
                    that.val("").removeClass('placeholder');
                }
            })
            .blur(function(){
                if(that.val()===""){
                    that.val(text).addClass('placeholder');
                }
            })
            .closest('form').submit(function(){
            if(that.val() === text){
                that.val('');
            }
        });
    });
}



//è§å±åºåå·¦å³ç®­å¤´
$(".playingVideo .video_left .video_btn_l").click(function(e){

    $(this).toggleClass("video_btn_r");
    if($(this).hasClass("video_btn_r")){
        $(".playingVideo .video_left").animate({"width":"1200px"},500);
        $(".playingVideo .video_left .video_flash").animate({"width":"1200px"},500);
        $(".playingVideo .video_right").animate({"width":"0px"},500,function(){$(this).hide()});
        $(".playingVideo .video_right .boxs .thumb").css({"width":"0"});
    }else{
        //jiemudan();
        $(".playingVideo .video_right").show();
        $(".playingVideo .video_left").animate({"width":"940px"},500);
        $(".playingVideo .video_left .video_flash").animate({"width":"940px"},500);
        $(".playingVideo .video_right").animate({"width":"260px"},500);
        $(".playingVideo .video_right .boxs .thumb").css({"width":"3px"});

    }
    e.preventDefault();

})

/*å°è§é¢å¼¹çªææ½*/
setTimeout(function() {
    //alert(browser.versions.mobile)
    if(browser.versions.mobile){

    }else{
        scrollToTop();

    }
},300);
/*å°çªæ­æ¾å¨*/
var browser ={
    versions:function(){
        var ua = navigator.userAgent;
        return {
            ipad: ua.indexOf('ipad') > -1,
            iphone: ua.indexOf('iPhone') > -1,
            android: ua.indexOf('Android') > -1,
            mobile:/AppleWebKit.*Mobile/i.test(ua) ||/Android/i.test(ua)|| (/MIDP|SymbianOS|NOKIA|SAMSUNG|LG|NEC|TCL|Alcatel|BIRD|DBTEL|Dopod|PHILIPS|HAIER|LENOVO|MOT-|Nokia|SonyEricsson|SIE-|Amoi|ZTE/.test(ua)),
            isIE7:/MSIE 7.0|MSIE 8.0|MSIE/i.test(ua),
            WinPhone:/Windows Phone/i.test(ua)
        }
    }()
}

var moveLeft = '';
var moveTop = '';
var fix = false;
var fixm = false;
var appendsmall=true;
var bannerTop = 670;	//æ»å¨è³é¡¶é¨è·ç¦»
var dragWidth = 428;	//å°çªæ­æ¾å¨divå®½
var dragHeight = 240;	//å°çªæ­æ¾å¨divé«
function Drag(){
    var oDiv1=document.getElementById('player01');
    var oDiv=document.getElementById('player');
    oDiv1.onmousedown=function (ev){
        var oEvent=ev||event;
        var disX=oEvent.clientX-oDiv.offsetLeft;
        var disY=oEvent.clientY-oDiv.offsetTop;
        if(fix){
            document.onmousemove=function (ev){

                var oEvent=ev||event;
                var l=oEvent.clientX-disX;
                var t=oEvent.clientY-disY;

                var maxL = document.documentElement.clientWidth - oDiv.offsetWidth;
                var maxT = document.documentElement.clientHeight - oDiv.offsetHeight;

                l <= 0 && (l = 0);
                t <= 0 && (t = 0);
                l >= maxL && (l = maxL);
                t >= maxT && (t = maxT);
                oDiv.style.left=l+'px';
                oDiv.style.top=t+'px';
                moveTop=t;
                moveLeft=l;
                fixm = true;
                return false
            };
            document.onmouseup=function (){
                document.onmousemove=null;
                document.onmouseup=null;
                this.releaseCapture && this.releaseCapture()
            };
            this.setCapture && this.setCapture();
            return false
        };
    }
};
function scrollToTop() {
    var sTop = 0,
        pageHeight = document.documentElement.clientHeight;
    addOnscroll(function() {
        sTop = document.body.scrollTop || document.documentElement.scrollTop;
        if( sTop < bannerTop){

            if($("#flashplayer_player").length > 0){
                if($(".playingVideo .video_left .video_btn_l").hasClass("video_btn_r")){
                    $("#player").css({"position":"relative","width":"1200","height":"529","top":"0","left":"0","right":"auto","margin":"0 auto"});
                    $(".playingVideo .video_right").css({"width":"0px"});
                    $(".playingVideo .video_right .boxs .thumb").css({"width":"0px"});
                }else{
                    $("#player").css({"position":"relative","width":"940","height":"529","top":"0","left":"0","right":"auto","margin":"0"});
                    $(".playingVideo .video_right").css({"width":"260px"});
                    $(".playingVideo .video_right .boxs .thumb").css({"width":"3px"});
                }

            }else{
                hideLivePlayerSmallWindow("player");//æ¶å¤±å°çª

            }

            fix = false;
            $(".dragLayer").hide();


        }else{

            if(appendsmall && $("#flashplayer_player").length > 0){
                fix = true;
                if( $("#player").find(".dragLayer").length==0){
                    $("#player").append('<div class="dragLayer no-select" id="player01"><div style="height:110px;" class="no-con"></div><span class="no-text">æä½ç»é¢å¯æå¨å°çª</span><span class="no-close"></span></div>');
                }
                $(".dragLayer").show();
                $(".dragLayer").attr("style","");
                if(!fixm){
                    Drag();                                                $("#player").css({"position":"fixed","bottom":"0","width":dragWidth,"height":dragHeight,"left":"auto","top":"auto","right":"0"})
                }else{                                                   $("#player").css({"position":"fixed","width":dragWidth,"height":dragHeight,"top":moveTop,"left":moveLeft})
                }
            }else{
                if(appendsmall && typeof(livePlayerObjs["player"])!=="undefined" && livePlayerObjs["player"].adCallsIsPlayed){

                    if(livePlayerObjs["player"] && !livePlayerObjs["player"].isShowSmallWindow) { //2019.12.17æ°å¢
                        return;
                    }

                    fix = true;
                    if( $("#player").find(".dragLayer").length==0){
                        $("#player").append('<div class="dragLayer no-select" id="player01"><div style="height:110px;" class="no-con"></div><span class="no-text">æä½ç»é¢å¯æå¨å°çª</span><span class="no-close"></span></div>');
                    }
                    $(".dragLayer").show();
                    $(".dragLayer").attr("style","");
                    showLivePlayerSmallWindow("player");
                    if(!fixm){
                        Drag();                                               $("#player").css({"position":"fixed","bottom":"0","width":dragWidth,"height":dragHeight,"left":"auto","top":"auto","right":"0"})
                    }else{                                             $("#player").css({"position":"fixed","width":dragWidth,"height":dragHeight,"top":moveTop,"left":moveLeft})
                    }

                }
            }
        }
    });
}
function addOnscroll(fn) {
    if(typeof window.onscroll == 'function') {
        var tempFn = window.onscroll;
        window.onscroll = function() {
            tempFn();
            fn();
        }
    }else{
        window.onscroll = function() {
            fn();
        }
    }
}
/*å°çªæ­æ¾å¨end*/
/*å°æ­æ¾å¨å³é­*/
$(".no-close").live("click",function(){
    if($("#flashplayer_player").length > 0){
        $("#player").css({"position":"relative","width":"608","height":"357","top":"0","left":"0","right":"auto"});
    }else{
        hideLivePlayerSmallWindow("player");//æ¶å¤±å°çª
    }

    appendsmall=false;
})

/***å¾®ä¿¡åäº«***/
$(".video_btnBar .leftBar .share .share_box .barweixin").click(function(){
    if ($("#barweixin_weixin").length > 0) {
        $("#barweixin_weixin").remove()
    }else{

        requireQrcode2();
    }

})
function requireQrcode2() {
    if ($("#barweixin_weixin").length > 0) {
        $("#barweixin_weixin").show()
    } else {

        createTableCode2()
    }
    function createTableCode2() {

        var ss ='<div id="barweixin_weixin" class="barweixin_weixin">';
        ss += '<div class="barweixin_head">';
        ss += '</div><div class="sharebg">';
        ss += '<div id="codeqr2" class="wxcodeqr">';
        ss += "</div>";
        ss += '<div class="barweixin_headfoot">æ«ä¸æ« åäº«å°å¾®ä¿¡</div>';
        ss += "</div></div> ";
        $(".video_btnBar .leftBar .share .share_box .icon").append(ss);
        var ua = "canvas";
        if (navigator.userAgent.indexOf("MSIE") > -1) {
            ua = "table"
        }

        jQuery("#codeqr2").qrcode({
            render: ua,
            foreground: "#000",
            background: "#FFF",
            width: 102,
            height: 102,
            text: window.location.href
        });

    }
}

$(".video_btnBar .leftBar .share .share_box").on("mouseover",function(){
    $(".barweixin_weixin").remove();
})


/*è§é¢ç¹èµ*/
function dianzan(){
    var url='//api.itv.cntv.cn/praise/add?type=other&id='+zhiboliu+'&num=1';
    $.ajax({
        url:url,
        type:'get',
        dataType:"jsonp",
        jsonp: "jsonp_callback",
        jsonpCallback:zhiboliu,
        cache:true,
        success:function(data){
            var renshu = data.data.num;
            if (renshu>99999 && renshu<=99999999 ){
                renshu = parseInt(renshu/10000);
                renshu = "<i></i>"+renshu+"W+";
            }else if (renshu>99999999 && renshu<=999999999999){
                renshu = parseInt(renshu/100000000);
                renshu = "<i></i>"+renshu+"äº¿+";
            }else if (renshu>999999999999){
                renshu = "<i></i>"+"9999äº¿+";
            }else{
                renshu = data.data.num;
                renshu = "<i></i>"+renshu;
            }
            $("#zannum").html(renshu);
            $("#zannum").addClass("cur");
            $("#zannum").siblings(".clickDiscuss").show().delay("1500").fadeOut();
        }
    });
}
function getRenshu() {
    var url = '//common.itv.cntv.cn/praise/get?type=other&id='+zhiboliu+'&time='+new Date().getMinutes();
    $.ajax({
        url: url,
        dataType: "jsonp",
        jsonp: "jsonp_callback",
        jsonpCallback:zhiboliu,
        cache:true,
        error: function() {},
        success: function(data) {
            var renshu = data.data.num;
            if (renshu>99999 && renshu<=99999999 ){
                renshu = parseInt(renshu/10000);
                renshu = "<i></i>"+renshu+"W+";
            }else if (renshu>99999999 && renshu<=999999999999){
                renshu = parseInt(renshu/100000000);
                renshu = "<i></i>"+renshu+"äº¿+";
            }else if (renshu>999999999999){
                renshu = "<i></i>"+"9999äº¿+";
            }else{
                renshu = data.data.num;
                renshu = "<i></i>"+renshu;
            }
            $("#zannum").html(renshu);
        }
    });
}
$(function(){
    //getRenshu()
    /*ä¸»æäººç¹èµ*/
    $(".jiemuguanwang18043_zhibo_ind03 ul li .fab a").click(function(){
        var zan_id = $(this).attr("id");
        var that_ = $(this);
        $.ajax({
            url:'//api.itv.cntv.cn/praise/add?type=compere&id='+zan_id+'&num=1',
            dataType:"jsonp",
            jsonp: "jsonp_callback",
            jsonpCallback:zan_id,
            cache:true,
            success:function(data){
                var zanshu = data.data.num;
                if (zanshu>99999){
                    zanshu = parseInt(zanshu/10000);
                    zanshu = ""+zanshu+"W+";
                }else{
                    zanshu = data.data.num;
                }
                that_.html("<i class='icon'></i>"+zanshu+'<span class="discuss" style="display: none;">+1</span>');
                that_.find(".discuss").show();
                that_.find(".discuss").stop(this,this);
                that_.find(".discuss").show().delay("1500").fadeOut();



            }
        });
    })
    $(".jiemuguanwang18043_zhibo_ind03 ul li").each(function(){
        var quzan_id = $(this).find(".fab a").attr("id");
        $.ajax({
            url:'//common.itv.cntv.cn/praise/get?type=compere&id='+quzan_id+'&time='+new Date().getMinutes(),
            dataType:"jsonp",
            jsonp: "jsonp_callback",
            jsonpCallback:quzan_id,
            cache:true,
            success: function(data){
                var zanshu = data.data.num;
                if (zanshu>99999){
                    zanshu = parseInt(zanshu/10000);
                    zanshu = ""+zanshu+"W+";
                }else{
                    zanshu = data.data.num;
                }
                $("#"+quzan_id).html("<i class='icon'></i>"+zanshu+'<span class="discuss" style="display: none;">+1</span>');
            }
        });
    });

    /*å¤®è§å½±é³åºé¨äºç»´ç  */
    var oCon6 = $(".taiwang18043_con06").find("ul li");
    oCon6.hover(function(){
        $(this).find("img").eq(1).animate({width:"0"},100,function(){
            $(this).parent().siblings().find("img").animate({width:"102px"},100);
        });
    },function(){
        $(this).find("img").eq(0).animate({width:"0"},100,function(){
            $(this).parent().siblings().find("img").animate({width:"121px"},100);
            oCon6.find("img").eq(0).css("width","0")
        });
    })
})


