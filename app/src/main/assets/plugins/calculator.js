/* @plugin
{
  "id": "builtin.calculator",
  "name": "Калькулятор",
  "version": "1.0",
  "author": "Expressive Keyboard",
  "description": "Мод: калькулятор прямо в клавиатуре. Результат вставляется в поле одной кнопкой.",
  "icon": "@calc"
}
*/

var expr = "";
var result = "";

function calc(s) {
  var clean = s.replace(/×/g, "*").replace(/÷/g, "/").replace(/,/g, ".").replace(/−/g, "-");
  if (!clean || !/^[0-9+\-*\/().%\s]+$/.test(clean)) return null;
  clean = clean.replace(/(\d+(\.\d+)?)%/g, "($1/100)");
  try {
    var v = Function("return (" + clean + ");")();
    if (typeof v !== "number" || !isFinite(v)) return null;
    return String(Math.round(v * 1e10) / 1e10);
  } catch (e) {
    return null;
  }
}

function press(k) {
  if (k === "C") { expr = ""; result = ""; }
  else if (k === "⌫") expr = expr.slice(0, -1);
  else if (k === "=") { var r = calc(expr); if (r !== null) { expr = r; result = ""; } }
  else expr += k;
  if (k !== "=") { var p = calc(expr); result = p === null || p === expr ? "" : p; }
  kb.refresh();
}

kb.addPanel({
  id: "calc",
  title: "Калькулятор",
  icon: "@calc",
  render: function () {
    return [
      { type: "text", text: expr || "0", size: 26, bold: true },
      { type: "text", text: result ? "= " + result : " ", size: 15 },
      // ровно 4 колонки: иначе на другом dpi в ряд помещается 5 кнопок и раскладка «прыгает»
      { type: "grid", columns: 4, height: 42,
        items: ["7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "-", "0", ".", "%", "+", "(", ")", "C", "⌫"],
        onClick: function (k) { press(k); } },
      { type: "row", children: [
        { type: "button", text: "=", accent: true, onClick: function () { press("="); } },
        { type: "button", text: "Вставить", icon: "@send", onClick: function () {
          var r = calc(expr);
          if (r !== null) kb.commit(r);
        } },
        { type: "button", text: "Вставить с примером", onClick: function () {
          var r = calc(expr);
          if (r !== null) kb.commit(expr + " = " + r);
        } }
      ] }
    ];
  }
});
