package app.verdant.search

/** User-selected timestamps use the public iframe seek API; pending seeks wait for readiness. */
internal val PLAYER_SEEK_SCRIPT = """
function MinimalTubeSeek(getPlayer, emit) {
  var ready = false, pending = null;
  function flush() {
    if (!ready || pending === null || document.hidden) return false;
    var player = getPlayer();
    if (!player || typeof player.seekTo !== 'function') return false;
    try {
      var seconds = pending, duration = player.getDuration();
      if (Number.isFinite(duration) && duration > 0) seconds = Math.min(seconds, duration);
      player.seekTo(seconds, true);
      pending = null;
      emit('seek', Math.floor(seconds));
      return true;
    } catch (_) { return false; }
  }
  return {
    seek: function(seconds) {
      if (!Number.isInteger(seconds) || seconds < 0 || seconds > 2147483647) return 'invalid';
      pending = seconds;
      return flush() ? 'applied' : 'queued';
    },
    // Relative skip for the app's own back/forward buttons, bounded to the video.
    seekBy: function(delta) {
      if (!Number.isInteger(delta) || !ready) return 'unavailable';
      var player = getPlayer();
      try {
        var now = player.getCurrentTime(), duration = player.getDuration();
        if (!Number.isFinite(now)) return 'unavailable';
        var target = Math.max(0, Math.floor(now + delta));
        if (Number.isFinite(duration) && duration > 1) target = Math.min(target, Math.floor(duration) - 1);
        pending = target;
        return flush() ? 'applied' : 'queued';
      } catch (_) { return 'unavailable'; }
    },
    ready: function() { ready = true; flush(); },
    flush: flush
  };
}
""".trimIndent()
