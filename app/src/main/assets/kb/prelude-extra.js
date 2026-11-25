// Extra kb.* helpers written in JavaScript on top of the host functions (loaded after the main prelude).
// ES5 only: the same file runs in QuickJS and in Rhino. tools/plugin-test runs it in Node against a mock of kb.
(function () {
  "use strict";
  var parse = JSON.parse, stringify = JSON.stringify;

  // ------------------------------------------------------------------ kb.require: several files in one plugin
  // Put helpers in the plugin's folder / .zip (maze.js, lib/math.js ...) and load them from the main script:
  //   var maze = kb.require("lib/maze.js");        // path from the plugin root
  //   var m2 = kb.require("./sibling");            // relative to the file that calls it; ".js" and "/index.js" are optional
  // A file sets module.exports (or fills exports). Every file runs once per plugin load and is cached;
  // circular requires get the half-filled exports, like in Node. A ".json" file returns the parsed data.
  function normalize(path) {
    var out = [], parts = String(path).split("/");
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i];
      if (p === "" || p === ".") continue;
      if (p === "..") { if (!out.length) throw new Error("require: \"" + path + "\" leaves the plugin folder"); out.pop(); }
      else out.push(p);
    }
    return out.join("/");
  }

  function dirOf(path) { var i = path.lastIndexOf("/"); return i < 0 ? "" : path.slice(0, i); }

  var modules = Object.create(null);
  var asset = kb.asset;

  function load(resolved) {
    var tried = [resolved, resolved + ".js", resolved + "/index.js", resolved + ".json"];
    for (var i = 0; i < tried.length; i++) {
      var path = tried[i];
      if (modules[path]) return modules[path].exports;
      var src = asset(path);
      if (typeof src !== "string" || src.indexOf("data:") === 0) continue;
      var module = { exports: {}, id: path };
      modules[path] = module;
      if (/\.json$/.test(path)) {
        try { module.exports = parse(src); } catch (e) { delete modules[path]; throw new Error("require(\"" + path + "\"): bad JSON: " + e.message); }
        return module.exports;
      }
      var fn;
      try {
        fn = new Function("module", "exports", "require", "kb", src + "\n//# sourceURL=" + path);
      } catch (e) {
        delete modules[path];
        throw new Error("require(\"" + path + "\"): " + e.message);
      }
      try {
        fn.call(module.exports, module, module.exports, makeRequire(dirOf(path)), kb);
      } catch (e) {
        delete modules[path];
        if (e && typeof e === "object" && String(e.message).indexOf(path) < 0) e.message = path + ": " + e.message;
        throw e;
      }
      return module.exports;
    }
    throw new Error("require: no file \"" + resolved + "\" in the plugin (looked for " + tried.join(", ") + ")");
  }

  function makeRequire(dir) {
    return function (spec) {
      spec = String(spec);
      var rel = spec.charAt(0) === ".";
      return load(normalize(rel && dir ? dir + "/" + spec : spec));
    };
  }

  kb.require = makeRequire("");

  // ------------------------------------------------------------------ kb.saves: named save slots and high scores
  // The host stores them in a separate file that Android's backup includes, so progress can survive a reinstall.
  // For a sure copy use exportText() / importText(), or share() / copy().
  var n = kb.saves;
  if (n && n.rawGet) {
    var NAME = /^[A-Za-z0-9_. \-]{1,40}$/;
    var SLOT = "slot.", SCORE = "hs.";

    var check = function (name, what) {
      if (typeof name !== "string" || !NAME.test(name)) {
        throw new Error("saves: bad " + what + " name \"" + name + "\" (1-40 letters, digits, space, . _ -)");
      }
      return name;
    };
    var read = function (key) {
      var r = n.rawGet(key);
      if (r === null || r === undefined) return null;
      try { return parse(r); } catch (e) { return null; }
    };
    var write = function (key, value) { n.rawSet(key, stringify(value)); };
    var keysWith = function (prefix) {
      var all = n.rawKeys() || [], out = [];
      for (var i = 0; i < all.length; i++) if (all[i].indexOf(prefix) === 0) out.push(all[i].slice(prefix.length));
      return out;
    };

    var saves = {
      // saves.save("run1", { level: 3, hp: 40 }, "Level 3 · hospital")  — any JSON-friendly value, up to ~256 KB
      save: function (slot, data, title) {
        check(slot, "slot");
        write(SLOT + slot, { v: 1, at: Date.now(), title: title === undefined ? slot : String(title), data: data });
        return true;
      },
      load: function (slot, fallback) {
        check(slot, "slot");
        var o = read(SLOT + slot);
        return o && o.v === 1 ? o.data : fallback;
      },
      has: function (slot) { check(slot, "slot"); return n.rawGet(SLOT + slot) !== null; },
      remove: function (slot) { check(slot, "slot"); n.rawRemove(SLOT + slot); return true; },
      // [{ slot, title, at, bytes }] newest first
      list: function () {
        var names = keysWith(SLOT), out = [];
        for (var i = 0; i < names.length; i++) {
          var raw = n.rawGet(SLOT + names[i]);
          var o = null;
          try { o = raw ? parse(raw) : null; } catch (e) { o = null; }
          if (o && o.v === 1) out.push({ slot: names[i], title: o.title, at: o.at, bytes: raw.length });
        }
        out.sort(function (a, b) { return b.at - a.at; });
        return out;
      },

      // saves.highScore("maze", 4200, { name: "Ann", max: 10, lower: false }) -> place 1..max, or 0 if it did not make the list
      highScore: function (board, score, o) {
        check(board, "board");
        o = o || {};
        if (typeof score !== "number" || score !== score) throw new Error("saves.highScore: score must be a number");
        var max = Math.max(1, Math.min(100, o.max || 10)), lower = !!o.lower;
        var list = read(SCORE + board) || [];
        var entry = { score: score, name: o.name === undefined ? "" : String(o.name).slice(0, 40), at: Date.now() };
        var place = list.length;
        for (var i = 0; i < list.length; i++) {
          if (lower ? score < list[i].score : score > list[i].score) { place = i; break; }
        }
        if (place >= max) return 0;
        list.splice(place, 0, entry);
        if (list.length > max) list.length = max;
        write(SCORE + board, list);
        return place + 1;
      },
      highScores: function (board) { check(board, "board"); return read(SCORE + board) || []; },
      clearHighScores: function (board) { check(board, "board"); n.rawRemove(SCORE + board); return true; },
      boards: function () { return keysWith(SCORE); },

      // A text you can keep anywhere (a file, a message to yourself). Contains every slot and every high-score board.
      exportText: function () {
        var out = { kb: "saves", v: 1, plugin: kb.pluginId, at: Date.now(), slots: {}, scores: {} };
        var s = keysWith(SLOT), h = keysWith(SCORE), i;
        for (i = 0; i < s.length; i++) out.slots[s[i]] = n.rawGet(SLOT + s[i]);
        for (i = 0; i < h.length; i++) out.scores[h[i]] = n.rawGet(SCORE + h[i]);
        return stringify(out);
      },
      // saves.importText(text, { overwrite: "newer" | true | false }) -> { slots, boards, skipped }
      // "newer" (default) keeps the newer save of a slot; high scores are merged and sorted again.
      importText: function (text, o) {
        o = o || {};
        var mode = o.overwrite === undefined ? "newer" : o.overwrite;
        var data;
        try { data = parse(String(text)); } catch (e) { throw new Error("saves.importText: this is not saves text"); }
        if (!data || data.kb !== "saves" || data.v !== 1 || typeof data.slots !== "object") throw new Error("saves.importText: this is not saves text");
        var res = { slots: 0, boards: 0, skipped: 0 }, names, i;
        names = Object.keys(data.slots || {});
        for (i = 0; i < names.length; i++) {
          var name = names[i], raw = data.slots[name], inc = null;
          if (!NAME.test(name) || typeof raw !== "string") { res.skipped++; continue; }
          try { inc = parse(raw); } catch (e) { inc = null; }
          if (!inc || inc.v !== 1) { res.skipped++; continue; }
          var cur = read(SLOT + name);
          if (cur && (mode === false || (mode === "newer" && cur.at >= inc.at))) { res.skipped++; continue; }
          n.rawSet(SLOT + name, raw);
          res.slots++;
        }
        names = Object.keys(data.scores || {});
        for (i = 0; i < names.length; i++) {
          var board = names[i], list = null;
          if (!NAME.test(board) || typeof data.scores[board] !== "string") { res.skipped++; continue; }
          try { list = parse(data.scores[board]); } catch (e) { list = null; }
          if (!list || !list.length) { res.skipped++; continue; }
          var have = read(SCORE + board) || [], seen = {}, merged = [], j;
          var all = have.concat(list);
          for (j = 0; j < all.length; j++) {
            var e2 = all[j], id = e2.score + "|" + e2.name + "|" + e2.at;
            if (!seen[id]) { seen[id] = 1; merged.push(e2); }
          }
          var lower = !!o.lower;
          merged.sort(function (a, b) { return lower ? a.score - b.score : b.score - a.score; });
          if (merged.length > 100) merged.length = 100;
          write(SCORE + board, merged);
          res.boards++;
        }
        return res;
      },
      // needs the "launch" permission
      share: function () { return kb.share(saves.exportText()); },
      // needs the "clipboard" permission
      copy: function () { kb.copy(saves.exportText()); return true; }
    };
    kb.saves = saves;
  }
})();
