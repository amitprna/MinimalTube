package app.verdant.search

val defaultPlaybackRates=listOf(0.5,0.75,1.0,1.25,1.5,1.75,2.0)
fun validPlaybackRate(rate:Double)=rate.isFinite() && rate in 0.25..8.0
fun playbackRateLabel(rate:Double)=rate.toString().removeSuffix(".0")+"×"

// Only public iframe API methods. A short transition guard prevents an automatic 1x reset
// from replacing the user's preference, while stable player-control changes are adopted.
internal val PLAYER_SPEED_SCRIPT = """
function MinimalTubeSpeed(getPlayer, initialRate, emit) {
    var preferred=initialRate, state=-1, transitionUntil=0, pending=null, timer=null, stale=false;
    function rates() {
        try {return getPlayer().getAvailablePlaybackRates().filter(function(r){return Number.isFinite(r)&&r>=0.25&&r<=8})} catch(e){return [1]}
    }
    function actual() {try {return getPlayer().getPlaybackRate()} catch(e){return 1}}
    function report() {emit('rate',actual());emit('rates',JSON.stringify(rates()))}
    function target() {
        var supported=rates();
        if(supported.indexOf(preferred)>=0)return preferred;
        var towardsNormal=supported.filter(function(r){return preferred>1?r>=1&&r<=preferred:r<=1&&r>=preferred});
        return towardsNormal.length?(preferred>1?Math.max.apply(null,towardsNormal):Math.min.apply(null,towardsNormal)):1;
    }
    function apply() {
        var next=target();
        if(rates().indexOf(next)<0)return;
        if(Math.abs(actual()-next)>0.001){pending=next;getPlayer().setPlaybackRate(next)}
        report();
    }
    // After the app returns from the background the media can play at 1x while the iframe still
    // reports the old rate, so setting the same rate is ignored. Step through 1x to force it.
    function reassert() {
        var next=target(), supported=rates();
        if(supported.indexOf(next)<0)return;
        transitionUntil=Date.now()+1500;
        try {
            if(Math.abs(next-1)>0.001&&supported.indexOf(1)>=0){pending=1;getPlayer().setPlaybackRate(1)}
            pending=next;getPlayer().setPlaybackRate(next);
        } catch(e) {}
        report();
    }
    function clearTimer() {if(timer!==null){clearTimeout(timer);timer=null}}
    return {
        ready:function(){transitionUntil=Date.now()+750;apply()},
        select:function(rate){
            if(rates().indexOf(rate)<0)return;
            preferred=rate;emit('preferred',preferred);apply();
        },
        stateChanged:function(next){
            state=next;transitionUntil=Date.now()+750;pending=null;clearTimer();
            if(next===1){
                if(stale){stale=false;reassert()} else apply();
                // Some iframe versions reset again just after playback resumes. Reapply once, never poll.
                timer=setTimeout(function(){timer=null;if(state===1)apply()},350);
            }
        },
        rateChanged:function(rate){
            emit('rate',rate);
            if(!Number.isFinite(rate)||rates().indexOf(rate)<0)return;
            if(pending!==null&&Math.abs(rate-pending)<0.001){pending=null;return}
            if(typeof document!=='undefined'&&document.hidden)return;
            var playerState;try{playerState=getPlayer().getPlayerState()}catch(e){playerState=state}
            // Respect deliberate changes through YouTube's own menu outside a state transition.
            if(playerState===state&&Date.now()>=transitionUntil&&(state===1||state===2)){
                preferred=rate;pending=null;emit('preferred',preferred);
            }
        },
        resumed:function(){stale=true;if(state===1){stale=false;reassert()}},
        dispose:clearTimer
    };
}
""".trimIndent()
