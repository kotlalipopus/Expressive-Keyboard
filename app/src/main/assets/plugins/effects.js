/* @plugin
{
  "id": "builtin.effects",
  "name": "Эффекты",
  "version": "1.0",
  "author": "Expressive Keyboard",
  "description": "Пример мода на API 4: искры и жидкость отскакивают от клавиш и реагируют на палец, шейдер, наклон клавиатуры. Настройки разбиты на вкладки, есть пресеты.",
  "icon": "@magic",
  "enabledByDefault": false,
  "permissions": ["sensors"],
  "config": [
    { "type": "tab", "title": "Частицы" },
    { "id": "sparks", "title": "Искры под пальцем", "type": "toggle", "default": true },
    { "id": "spark_color", "title": "Цвет искр", "type": "color", "default": "#FFB74D", "showIf": "sparks" },
    { "id": "liquid", "title": "Жидкость", "type": "toggle", "default": false, "description": "Течёт вслед за наклоном телефона и обтекает клавиши" },
    { "type": "section", "title": "Настройка жидкости", "collapsed": true },
    { "id": "liquid_count", "title": "Количество", "type": "slider", "min": 200, "max": 3000, "step": 100, "default": 1000, "showIf": "liquid" },
    { "id": "liquid_color", "title": "Цвет", "type": "color", "default": "#664FC3F7", "showIf": "liquid" },

    { "type": "tab", "title": "Шейдер" },
    { "id": "wave", "title": "Волна по клавиатуре", "type": "toggle", "default": false, "description": "Android 13 и новее" },
    { "id": "wave_power", "title": "Сила волны", "type": "slider", "min": 1, "max": 16, "step": 1, "default": 4, "showIf": "wave" },

    { "type": "tab", "title": "Наклон" },
    { "id": "parallax", "title": "Наклон за телефоном", "type": "toggle", "default": false },
    { "id": "parallax_power", "title": "Сила наклона", "type": "slider", "min": 2, "max": 30, "step": 1, "default": 10, "showIf": "parallax" },

    { "type": "tab", "title": "Пресеты" },
    { "id": "preset_calm", "type": "preset", "title": "Спокойный", "values": { "sparks": false, "liquid": false, "wave": false, "parallax": false } },
    { "id": "preset_dev", "type": "preset", "title": "Всё сразу", "values": { "sparks": true, "liquid": true, "wave": true, "parallax": true, "wave_power": 6 } },
    { "id": "import", "title": "Свой пресет (JSON)", "type": "textarea", "hint": "{ \"sparks\": true, \"liquid\": false }",
      "description": "Вставьте текст и нажмите галочку" }
  ]
}
*/

var WAVE = [
  "uniform shader content; uniform float iTime; uniform float power;",
  "half4 main(float2 p) {",
  "  p.x += sin(p.y * 0.035 + iTime * 2.5) * power;",
  "  return content.eval(p);",
  "}"
].join("\n");

function apply() {
  var c = kb.config();
  var size = kb.keyboardSize();

  if (c.sparks) {
    kb.particles.set("sparks", {
      max: 4000, size: 3, colors: [c.spark_color, "#FFFFFF"], gravity: 500, drag: 0.6, life: 0.9,
      bounds: "kill", collideKeys: true, emitOnTouch: 8, speed: 220, layer: "overlay"
    });
  } else kb.particles.remove("sparks");

  var wasLiquid = kb.particles.count("liquid") > 0;
  if (c.liquid) {
    kb.particles.set("liquid", {
      max: c.liquid_count, liquid: true, size: 7, color: c.liquid_color, gravity: 350, tilt: 900,
      bounce: 0.2, drag: 0.4, collideKeys: true, touchForce: 6000, touchRadius: 70
    });
    if (!wasLiquid && size.width > 0) kb.particles.emit("liquid", { count: c.liquid_count, x: 0, y: 0, w: size.width, h: size.height / 3 });
  } else kb.particles.remove("liquid");

  if (c.wave && kb.supports("postEffect")) kb.postEffect({ src: WAVE, uniforms: { power: c.wave_power } });
  else kb.postEffect(null);

  if (!c.parallax) kb.keyboardEffect(null);
}

kb.on("tilt", function (t) {
  if (!kb.config("parallax")) return;
  var k = kb.config("parallax_power");
  kb.keyboardEffect({ rotationY: t.x * k, rotationX: -t.y * k, camera: 20 });
});

kb.on("option", function (e) {
  if (e.id === "import") {
    if (!e.value) return;
    try {
      kb.setConfigs(JSON.parse(e.value));
    } catch (err) {
      kb.toast("Пресет: это не JSON");
    }
    return;
  }
  apply();
});

// при загрузке клавиатура может быть ещё без размеров: жидкость заполнит её при показе
kb.on("show", apply);
apply();
