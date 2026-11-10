// Run the exact JavaScript embedded by Android against simulated public iframe API events.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../android/app/src/main/java/app/verdant/search/PlayerSpeed.kt'), 'utf8');
const script = source.match(/internal val PLAYER_SPEED_SCRIPT = """\r?\n([\s\S]+?)\r?\n"""\.trimIndent\(\)/)[1];

function setup(initial = 2, available = [0.5, 1, 1.5, 2]) {
  let now = 0, serial = 0, actual = 1, state = -1, controller;
  const timers = new Map(), calls = [], events = [];
  let acceptRate = true;
  const context = vm.createContext({
    Date: { now: () => now },
    setTimeout: (fn, delay) => { const id = ++serial; timers.set(id, { at: now + delay, fn }); return id; },
    clearTimeout: id => timers.delete(id)
  });
  vm.runInContext(script, context);
  const player = {
    getAvailablePlaybackRates: () => available,
    getPlaybackRate: () => actual,
    getPlayerState: () => state,
    setPlaybackRate: rate => {
      assert(available.includes(rate), 'Only rates advertised by YouTube may be requested');
      calls.push(rate);
      if (acceptRate) { actual = rate; controller.rateChanged(rate); }
    }
  };
  controller = context.MinimalTubeSpeed(() => player, initial, (kind, value) => events.push([kind, value]));
  function tick(ms) {
    const end = now + ms;
    for (;;) {
      const next = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      now = next[1].at; timers.delete(next[0]); next[1].fn();
    }
    now = end;
  }
  return {
    controller, calls, events, tick,
    get actual() { return actual; },
    set accept(value) { acceptRate = value; },
    set available(value) { available = value; },
    state(value) { state = value; controller.stateChanged(value); },
    rateEvent(value) { actual = value; controller.rateChanged(value); },
    start() { controller.ready(); this.state(1); tick(1000); }
  };
}

test('default 2x is applied on ready and actual speed is reported', () => {
  const p = setup(); p.start();
  assert.equal(p.actual, 2); assert(p.events.some(([k, v]) => k === 'rate' && v === 2));
});
test('pause, automatic 1x reset, and resume restore 2x', () => {
  const p = setup(); p.start(); p.state(2); p.rateEvent(1); p.tick(5000); p.state(1);
  assert.equal(p.actual, 2);
});
test('replay after ending restores selected speed', () => {
  const p = setup(); p.start(); p.state(0); p.rateEvent(1); p.tick(5000); p.state(1);
  assert.equal(p.actual, 2);
});
test('a delayed reset just after resume is repaired without continuous polling', () => {
  const p = setup(); p.start(); p.state(2); p.state(1); p.tick(200); p.rateEvent(1); p.tick(200);
  assert.equal(p.actual, 2);
  const count = p.calls.length; p.tick(10000); assert.equal(p.calls.length, count);
});
test('explicit 1x selection while paused is retained on resume', () => {
  const p = setup(); p.start(); p.state(2); p.controller.select(1); p.state(1); p.tick(1000);
  assert.equal(p.actual, 1);
});
test('YouTube native menu changes during steady playback are respected', () => {
  const p = setup(); p.start(); p.rateEvent(1.5); p.state(2); p.rateEvent(1); p.state(1);
  assert.equal(p.actual, 1.5);
});
test('YouTube native menu changes while stably paused are respected', () => {
  const p = setup(); p.start(); p.state(2); p.tick(1000); p.rateEvent(1); p.state(1); p.tick(1000);
  assert.equal(p.actual, 1);
});
test('a video limited to 1x is never forced to an unsupported rate', () => {
  const p = setup(2, [1]); p.start(); p.controller.select(3);
  assert.equal(p.actual, 1); assert.equal(p.calls.length, 0);
});
test('3x works only when the iframe advertises it', () => {
  const p = setup(1, [1, 2, 3]); p.start(); p.controller.select(3); p.state(2); p.rateEvent(1); p.state(1);
  assert.equal(p.actual, 3);
});
test('fallback moves toward normal and later available 2x can be restored', () => {
  const p = setup(2, [1, 1.5]); p.start(); assert.equal(p.actual, 1.5);
  p.available = [1, 1.5, 2]; p.state(1); assert.equal(p.actual, 2);
});
test('declined rate changes show actual 1x and do not create a retry loop', () => {
  const p = setup(); p.accept = false; p.start(); p.tick(10000);
  assert.equal(p.actual, 1); assert(p.calls.length <= 3);
  assert(p.events.filter(([k]) => k === 'rate').every(([, v]) => v === 1));
});
test('rapid pause after resume cancels the delayed speed update', () => {
  const p = setup(); p.start(); p.state(2); p.state(1); p.state(2); p.rateEvent(1);
  const count = p.calls.length; p.tick(1000); assert.equal(p.calls.length, count);
});

test('after returning from the background a stale reported 2x is forced back on play', () => {
  const p = setup(); p.start(); p.state(2);
  // The iframe still reports 2x although the media silently fell back to 1x.
  const before = p.calls.length; p.controller.resumed(); p.state(1);
  assert.deepEqual(p.calls.slice(before), [1, 2]); assert.equal(p.actual, 2);
  assert(p.events.some(([k, v]) => k === 'preferred' && v === 1) === false, 'the forced step through 1x is not adopted as the preference');
});
test('returning while already playing re-applies the speed immediately, once', () => {
  const p = setup(); p.start(); const before = p.calls.length;
  p.controller.resumed(); assert.deepEqual(p.calls.slice(before), [1, 2]);
  p.state(2); p.state(1); p.tick(1000); assert.deepEqual(p.calls.slice(before), [1, 2]);
});
test('a preferred 1x needs no forced step after resume', () => {
  const p = setup(1); p.start(); p.state(2); const before = p.calls.length; p.controller.resumed(); p.state(1);
  assert.deepEqual(p.calls.slice(before), [1]); assert.equal(p.actual, 1);
});

// The timestamp controller uses only YouTube's public API, alongside the speed controller.
const seekSource = fs.readFileSync(path.join(__dirname, '../android/app/src/main/java/app/verdant/search/PlayerSeek.kt'), 'utf8');
const seekScript = seekSource.match(/internal val PLAYER_SEEK_SCRIPT = """\r?\n([\s\S]+?)\r?\n"""\.trimIndent\(\)/)[1];
function setupSeek() {
  const document = { hidden: false }, calls = [], events = [];
  let duration = 3600, fail = false, paused = false;
  const context = vm.createContext({ document });
  vm.runInContext(seekScript, context);
  let current = 0;
  const player = { getDuration: () => duration, getCurrentTime: () => current, seekTo: (seconds, allowSeekAhead) => {
    if (fail) throw new Error('Not ready'); calls.push([seconds, allowSeekAhead]);
  }};
  return { calls, events, document, controller: context.MinimalTubeSeek(() => player, (type,value) => events.push([type,value])),
    set duration(value) { duration = value; }, set fail(value) { fail = value; }, set current(value) { current = value; },
    set paused(value) { paused = value; }, get paused() { return paused; }
  };
}
test('timestamp seeks in the same player through seekTo with buffering allowed', () => {
  const p = setupSeek(); p.controller.ready();
  assert.equal(p.controller.seek(754), 'applied'); assert.deepEqual(p.calls, [[754, true]]);
  assert.deepEqual(p.events, [['seek', 754]]);
});
test('timestamps tapped before readiness retain only the latest requested position', () => {
  const p = setupSeek(); assert.equal(p.controller.seek(10), 'queued'); p.controller.seek(30);
  assert.equal(p.calls.length, 0); p.controller.ready(); assert.deepEqual(p.calls, [[30, true]]);
  p.controller.ready(); assert.equal(p.calls.length, 1);
});
test('summary seeks never invoke playVideo or alter a paused player', () => {
  const p = setupSeek(); p.paused = true; p.controller.ready(); p.controller.seek(90);
  assert.equal(p.paused, true); assert.deepEqual(p.calls, [[90, true]]);
});
test('a queued timestamp never starts playback while the document is hidden', () => {
  const p = setupSeek(); p.document.hidden = true; p.controller.seek(120); p.controller.ready();
  assert.equal(p.calls.length, 0); p.document.hidden = false; p.controller.flush();
  assert.deepEqual(p.calls, [[120, true]]);
});
test('invalid timestamps cannot reach the iframe and excessive valid positions are bounded to its duration', () => {
  const p = setupSeek(); p.controller.ready();
  for (const value of [NaN, Infinity, -1, 1.5, '90', 2147483648]) assert.equal(p.controller.seek(value), 'invalid');
  assert.equal(p.calls.length, 0); p.duration = 100; p.controller.seek(120);
  assert.deepEqual(p.calls, [[100, true]]);
});
test('a temporarily unavailable seek stays pending without a retry loop', () => {
  const p = setupSeek(); p.controller.ready(); p.fail = true;
  assert.equal(p.controller.seek(90), 'queued'); assert.equal(p.calls.length, 0);
  p.fail = false; p.controller.flush(); assert.deepEqual(p.calls, [[90, true]]);
});
test('skip buttons seek relative to the current position and stay inside the video', () => {
  const p = setupSeek(); p.controller.ready(); p.duration = 200;
  p.current = 50.6; assert.equal(p.controller.seekBy(10), 'applied');
  p.current = 4; p.controller.seekBy(-10);
  p.current = 195; p.controller.seekBy(10);
  assert.deepEqual(p.calls, [[60, true], [0, true], [199, true]]);
  assert.deepEqual(p.events.at(-1), ['seek', 199]);
});
test('skip buttons do nothing before the player is ready or with invalid input', () => {
  const p = setupSeek(); assert.equal(p.controller.seekBy(10), 'unavailable');
  p.controller.ready(); assert.equal(p.controller.seekBy(1.5), 'unavailable');
  p.current = NaN; assert.equal(p.controller.seekBy(10), 'unavailable');
  assert.equal(p.calls.length, 0);
});

// Watch-position reporting used to resume videos where the viewer stopped.
const progressSource = fs.readFileSync(path.join(__dirname, '../android/app/src/main/java/app/verdant/search/WatchProgress.kt'), 'utf8');
const progressScript = progressSource.match(/internal val PLAYER_PROGRESS_SCRIPT = """\r?\n([\s\S]+?)\r?\n"""\.trimIndent\(\)/)[1];
function setupProgress() {
  let time = 0, duration = 600, serial = 0;
  const intervals = new Map(), events = [];
  const context = vm.createContext({
    setInterval: (fn, delay) => { const id = ++serial; intervals.set(id, { fn, delay }); return id; },
    clearInterval: id => intervals.delete(id)
  });
  vm.runInContext(progressScript, context);
  const player = { getCurrentTime: () => time, getDuration: () => duration };
  const controller = context.MinimalTubeProgress(() => player, (type, value) => events.push([type, value]));
  const saved = () => events.filter(([type]) => type === 'time');
  const shown = () => events.filter(([type]) => type === 'pos');
  return { controller, events, intervals, saved, shown,
    set time(value) { time = value; }, set duration(value) { duration = value; },
    tick(count = 1) { for (let i = 0; i < count; i++) for (const { fn } of [...intervals.values()]) fn(); } };
}
test('nothing is reported before the viewer starts playback', () => {
  const p = setupProgress(); p.time = 0;
  assert.equal(p.controller.report(), false); p.controller.stateChanged(5); p.controller.stateChanged(2);
  assert.deepEqual(p.saved(), []);
});
test('the position to display is reported on ready and every second while playing', () => {
  const p = setupProgress(); p.controller.ready();
  assert.deepEqual(p.shown(), [['pos', '0:600']]);
  p.controller.stateChanged(1); p.time = 1.2; p.tick(); p.time = 2.1; p.tick(); p.tick();
  assert.deepEqual(p.shown(), [['pos', '0:600'], ['pos', '1:600'], ['pos', '2:600']]);
  assert.deepEqual(p.saved(), []);
  p.time = 40; p.controller.stateChanged(3);
  assert.deepEqual(p.shown().at(-1), ['pos', '40:600']);
});
test('playing reports periodically without repeats and pausing reports the exact position', () => {
  const p = setupProgress(); p.controller.stateChanged(1); assert.equal(p.intervals.size, 1);
  assert.equal([...p.intervals.values()][0].delay, 1000);
  p.time = 12.7; p.tick(5); p.tick(5); p.time = 17.2; p.tick(5);
  p.time = 20.9; p.controller.stateChanged(2);
  assert.deepEqual(p.saved(), [['time', '12:600:5'], ['time', '17:600:15'], ['time', '20:600:15']]);
  assert.equal(p.intervals.size, 0);
});
test('an explicit report while paused is always delivered for app backgrounding', () => {
  const p = setupProgress(); p.controller.stateChanged(1); p.time = 30; p.controller.stateChanged(2);
  assert.equal(p.controller.report(), true); assert.deepEqual(p.saved().at(-1), ['time', '30:600:0']);
});
test('ending a video records it as fully watched', () => {
  const p = setupProgress(); p.controller.stateChanged(1); p.time = 598; p.controller.stateChanged(0);
  assert.deepEqual(p.saved().at(-1), ['time', '600:600:0']); assert.equal(p.intervals.size, 0);
});
test('unreadable player values are ignored', () => {
  const p = setupProgress(); p.controller.stateChanged(1); p.time = NaN; p.tick(5); p.time = -4; p.tick(5);
  assert.deepEqual(p.events, []); p.time = 9; p.duration = NaN; p.tick(5);
  assert.deepEqual(p.saved(), [['time', '9:0:15']]);
});
