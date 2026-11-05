# Плагины и моды Expressive Keyboard

Плагин импортируется в **Настройки → Плагины и моды**: из файла или из буфера обмена. Бывает трёх видов:

| Файл | Что внутри |
|---|---|
| `.json` | Декларативный плагин: действия, сокращения, символы, темы, оформление, настройки, раскладки, иконки, языки. Может содержать и скрипт в поле `script` |
| `.js` | **Мод**: JavaScript, манифест — JSON в комментарии `/* @plugin { … } */` в начале файла |
| `.zip` | Мод с файлами: `plugin.json` + `main.js` + картинки и данные (до 8 МБ) |

Скрипт мода получает объект `kb` и может реагировать на нажатия и слова, вставлять и менять текст,
добавлять свои панели, кнопки, клавиши, подсказки, хранить данные, ходить в сеть, менять иконки, язык и настройки,
**рисовать и анимировать что угодно поверх клавиатуры**, знать, где какая клавиша, «нажимать» клавиши, менять раскладку
и оформление прямо на ходу. Например, можно сделать персонажа, который ходит по клавишам и печатает (пример ниже).
Скрипт выполняется в песочнице (JavaScript ES5 + большая часть ES6: `let`/`const`, стрелочные функции): без доступа
к Java и чужим файлам (у мода своя папка `kb.files`); то, что касается текста, сети, ссылок и ИИ, — только по разрешениям; каждый вызов ограничен по времени (300 мс, загрузка — 2 с), так что зависший мод не
подвесит клавиатуру. Ошибки и `console.log` видны в карточке плагина («Журнал скрипта»).

Встроенные примеры: моды «Заметки», «Калькулятор», «Ёлочки «»», «Студия» (пианино, рисование по клавиатуре, поле ввода, озвучка, свои настройки; `.js`), «Эффекты» (частицы, жидкость, шейдер, наклон, вкладки и пресеты настроек; `.js`), оформления «Жидкое стекло», «Неон»,
«Неоморфизм» (`app/src/main/assets/plugins/`). Шаблоны: **Сохранить пример мода (.js)** и **Сохранить пример плагина**.

На клавиатуре плагины открываются кнопкой 🧩 на панели инструментов (кнопка с сеткой слева вверху).

## Общие поля

| Поле | Обязательно | Описание |
|---|---|---|
| `id` | да | 2–64 символа: `A-Z a-z 0-9 . _ -`. Повторный импорт с тем же `id` обновляет плагин |
| `name` | да | Название |
| `version`, `author`, `description` | нет | Показываются в списке |
| `icon` | нет | Эмодзи, короткий текст, `@значок` или `data:image/png;base64,…` (см. «Значки») |
| `permissions` | нет | `network` — интернет, `read_text` — читать набираемый текст, `clipboard` — читать буфер обмена, `launch` — открывать ссылки, приложения и «Поделиться», `ai` — запросы к ИИ по ключу пользователя, `sensors` — наклон и вращение телефона, `microphone` — громкость и частоты с микрофона, `app_info` — в каком приложении печатают |
| `config` | нет | Свои настройки мода — появляются в его карточке (см. «Настройки мода») |
| `script` | нет | JavaScript мода (строка или массив строк) |
| `enabledByDefault` | нет | `false` — встроенный плагин появляется выключенным |

Должен быть хотя бы один из блоков `script`, `actions`, `shortcuts`, `symbols`, `theme`, `skin`, `settings`,
`layouts`, `variants`, `icons`, `languages` или `dictionary`.

При установке плагина с разрешениями клавиатура показывает, что именно он сможет делать.

# Моды: JavaScript API

```js
/* @plugin
{ "id": "my.mod", "name": "Мой мод", "permissions": ["read_text"] }
*/
kb.on("word", function (e) { console.log("набрано слово", e.word); });
kb.addButton({ title: "Привет", icon: "👋", toolbar: true, onClick: function () { kb.commit("Привет!"); } });
```

Полный пример — **Настройки → Плагины и моды → Сохранить пример мода (.js)**.

## События: `kb.on(событие, функция)`

| Событие | Аргумент | Что можно вернуть |
|---|---|---|
| `key` | `{ text }` — нажатая буква/символ | строку — ввести её вместо символа; `false` — отменить нажатие. *read_text* |
| `word` | `{ word, previous }` — слово только что дописано | — *read_text* |
| `suggest` | `{ word, previous }` — пока набирается слово | массив строк — подсказки по бокам строки подсказок. *read_text* |
| `space` / `enter` / `backspace` | — | `false` — отменить стандартное действие |
| `start` | `kb.field()` — открылось поле ввода | — *read_text* |
| `finish`, `show`, `hide` | — | поле закрылось / клавиатура показана / скрыта |
| `language` | код языка | — |
| `clipboard` | скопированный текст | — *clipboard* |
| `press` | `{ label, type }` — нажата любая клавиша (`a`, `space`, `enter`, `shift`…) | — *read_text* |
| `frame` | миллисекунды с прошлого кадра | ~30 раз в секунду, пока клавиатура на экране — для анимаций |
| `selection` | `{ start, end }` — курсор или выделение сдвинулись | — *read_text* |
| `pick` | `{ word }` — выбрана подсказка | `false` — не вставлять. *read_text* |
| `autocorrect` | `{ word, correction }` — пробел сейчас исправит слово (`correction` может быть `null`) | строку — своё исправление; `false` — не исправлять. *read_text* |
| `voice` | `{ text }` — распознана фраза голосом | строку — вставить её; `false` — не вставлять. *read_text* |
| `glide` | `{ word, alternatives }` — распознано слово свайпом | строку — вставить её; `false` — отменить. *read_text* |
| `panel` | `keyboard`, `emoji`, `clipboard`, `voice`, `plugins`, `translate`, `script_input`… | открылась панель |
| `shift` | `off`, `single`, `caps` | сменился Shift |
| `device` | `kb.device()` | поворот экрана, смена темы системы |
| `touch` | `{ type, x, y, pointer }`, `type` — `down`, `move`, `up` | касания клавиатуры, пока включено `kb.captureTouches()` |
| `message` | `{ from, name, data }` | сообщение от другого мода (`kb.broadcast`) |
| `option` | `{ id, value }` | поменялась настройка мода, нажата кнопка или пресет настроек |
| `pointer` | `{ type, x, y, pointer }`, `type` — `down`, `move`, `up` | палец на клавиатуре; клавиши при этом работают как обычно. Движения приходят до 30 раз в секунду. *read_text*: по координатам видно, что набирают |
| `tilt` | `{ x, y, z }`, примерно от −1 до 1 | наклон телефона, до 30 раз в секунду. *sensors* |
| `gyro` | `{ x, y, z }`, рад/с | вращение телефона. *sensors* |
| `audio` | `{ level, bands }`: 0..1 и 8 полос от низких к высоким | после `kb.audio.start()`, 20 раз в секунду. *microphone* |
| `app` | `{ package, name, color }`, `color` — основной цвет значка | открылось поле другого приложения. *app_info* |

В полях паролей и в режиме инкогнито `pointer`, `tilt`, `gyro`, `audio` и `app` не приходят. Датчики и микрофон работают,
только пока клавиатура на экране; при открытом голосовом вводе микрофон отдан ему. Сам звук мод не получает никогда.

`kb.off(событие)` снимает все обработчики события.

## Ввод и чтение текста

| Функция | Описание |
|---|---|
| `kb.commit(text)` | Вставить текст в поле |
| `kb.deleteBefore(n)` / `kb.deleteAfter(n)` | Удалить n символов до / после курсора |
| `kb.replaceBefore(n, text)` | Удалить n символов до курсора и вставить text |
| `kb.textBefore(n = 200)` / `kb.textAfter(n)` | Текст до / после курсора. *read_text* |
| `kb.selectedText()` / `kb.currentWord()` | Выделение / слово перед курсором. *read_text* |
| `kb.setSelection(start, end)` | Выделить или переставить курсор |
| `kb.key(name)` | `enter`, `space`, `backspace`, `tab`, `left`, `right`, `up`, `down`, `home`, `end`, `select_all`, `copy`, `cut`, `paste`, `undo`, `redo`, `delete_word`, `escape`, `shift`, `language` |
| `kb.setSuggestions([...], onPick)` | Показать свои слова в строке подсказок. С `onPick(word, index)` выбор достаётся моду, а не вставляется |
| `kb.setComposing(text)` / `kb.finishComposing()` | Своё «набираемое» (подчёркнутое) слово / закрепить его. Вместе с событием `key`, которое возвращает `false`, — свой способ ввода |
| `kb.cursor()` | `{ start, end }` — позиция курсора в поле. *read_text* |
| `kb.allText()` | Весь текст поля. *read_text* |
| `kb.replaceSelection(text)` / `kb.replaceAll(text)` | Заменить выделение / весь текст поля |
| `kb.sendKey(key, { ctrl, alt, shift, meta })` | Настоящая клавиша: `"a"`, `"f5"`, `"tab"`, `"escape"`, `"page_up"`, `"delete"` или код Android. `kb.sendKey("z", { ctrl: true })` — Ctrl+Z |
| `kb.editorAction(name)` | Действие поля: `search`, `send`, `go`, `next`, `previous`, `done` |
| `kb.commitImage(src)` | Вставить картинку (`data:image/…` или файл из .zip), если поле принимает картинки. Возвращает `true`/`false` |

## Клавиатура

| Функция | Описание |
|---|---|
| `kb.language()` / `kb.setLanguage(id)` / `kb.languages()` | Текущий язык / сменить / включённые языки |
| `kb.shift()` / `kb.setShift(s)` | `off`, `single`, `caps` |
| `kb.field()` | `{ package, inputType, password, multiLine, action, hint }` |
| `kb.openPanel(id)` / `kb.closePanel()` | `keyboard`, `emoji`, `clipboard`, `translate`, `gif`, `voice`, `plugins`, `plugin:<id мода>/<id панели>` |
| `kb.status(text)` / `kb.toast(text)` | Строка состояния на панели плагинов / всплывающее сообщение |
| `kb.vibrate(ms)` / `kb.hide()` | Вибрация / скрыть клавиатуру |
| `kb.clipboard()` / `kb.copy(text)` | История буфера (*clipboard*) / скопировать |
| `kb.setIcon(slot, spec)` / `kb.resetIcon(slot)` | Заменить иконку (слоты — в разделе `icons`) |
| `kb.settings.get(key)` / `kb.settings.set(key, value)` | Любая настройка из раздела `settings` |

## Свой интерфейс

```js
kb.addButton({ id, title, icon, toolbar: true, onClick })   // кнопка на вкладке мода и (toolbar) на панели инструментов
kb.removeButton(id)
kb.addPanel({ id, title, icon, render })                     // своя вкладка на панели плагинов
kb.refresh()                                                 // перерисовать панели (вызвать render заново)
kb.addCommand(name, fn)                                      // команда для клавиши раскладки { "label": "λ", "action": "name" }
```

`render()` возвращает массив элементов:

| Элемент | Поля |
|---|---|
| `{ type: "text" }` | `text`, `size`, `bold`, `color`, `center` |
| `{ type: "button" }` | `text`, `icon`, `accent`, `wide`, `onClick(value)`, `value` |
| `{ type: "row" }` / `{ type: "column" }` | `children: [...]` (ряд переносится на новую строку, если не помещается) |
| `{ type: "grid" }` | `items: ["😀", { text, icon, value }]`, `minWidth`, `height`, `columns`, `onClick(value)`. `columns` задаёт ровное число колонок (например, калькулятор: `columns: 4`), `minWidth` — минимальную ширину ячейки при переносе |
| `{ type: "image" }` | `src` (`data:image/…` или `kb.asset("pic.png")`), `size`, `onClick` |
| `{ type: "spacer" }` | `size` |
| `{ type: "input" }` | `title`, `hint`, `value`, `onChange(text)`, `onSubmit(text)` — по нажатию клавиатура печатает в это поле |
| `{ type: "toggle" }` | `text`, `checked`, `onChange(checked)` |
| `{ type: "slider" }` | `text`, `value`, `min`, `max`, `step`, `onChange(value)` (когда палец отпущен) |
| `{ type: "progress" }` | `value` 0..1; без `value` — бесконечная полоса |
| `{ type: "divider" }` | Разделитель |
| `{ type: "box" }` | Карточка: `children`, `color`, `radius`, `padding`, `border`, `onClick(value)`, `value` |
| `{ type: "tabs" }` | Вкладки-«таблетки»: `items` (как у `grid`), `selected`, `onClick(value)` |
| `{ type: "canvas" }` | Холст: `height`, `items` (элементы как у `kb.overlay`), `onTap({ x, y })` |
| `{ type: "scroll" }` | Прокрутка: `children`, `horizontal`, `height` (максимальная высота) |

Дополнительно: у `text` — `align` (`start`, `center`, `end`), `maxLines`, `mono`, `italic`; у `button` — `color`, `textColor`,
`onLongClick(value)`, `disabled`.

После нажатия на элемент панель перерисовывается автоматически.

### Ввод текста в моде

```js
kb.prompt({ title: "Поиск", hint: "Что найти?", value: "" }, function (text) {
  if (text === null) return;            // закрыли кнопкой ←
  kb.commit("Ищу: " + text);
});
kb.input({ title: "Имя" }).then(function (name) { kb.toast("Привет, " + name); });   // то же на промисе
kb.closePrompt();
```

Пока поле открыто, над клавиатурой стоит строка мода (← закрыть, ✓ отправить), а клавиши печатают в неё, а не в приложение.
Enter тоже отправляет. В обработчике можно сразу открыть следующее поле — так делаются диалоги в несколько шагов.

## Рисование поверх клавиатуры и клавиши

Слой мода лежит поверх клавиш и не мешает печатать. Координаты и размеры — в dp от левого верхнего угла клавиатуры.

| Функция | Описание |
|---|---|
| `kb.overlay([...])` / `kb.clearOverlay()` | Нарисовать набор элементов (заменяет прошлый) / убрать |
| `kb.background([...])` / `kb.clearBackground()` | То же, но **под клавишами**: поверх фона клавиатуры, клавиши остаются сверху — для живых фонов, волн, частиц. Стеклянные клавиши преломляют обычный фон темы, а не этот слой |
| `kb.keyboardSize()` | `{ width, height }` |
| `kb.keys()` | Все клавиши: `[{ label, type, x, y, w, h, cx, cy }]` (`label` — буква или `space`, `enter`, `shift`, `backspace`, `symbols`, `globe`, `emoji`) |
| `kb.keyRect(label)` | Прямоугольник одной клавиши или `null` |
| `kb.pressKey(label, { type: true })` | Анимация нажатия; `type: false` — только анимация, без ввода |
| `kb.colors()` | Текущие цвета: `background`, `key`, `keyText`, `func`, `funcText`, `accent`, `accentText` |
| `kb.setLayout(id, rows)` / `kb.setLayout(id, null)` | Своя раскладка на ходу (формат рядов — как в `layouts`) / вернуть обычную |
| `kb.setSkin({...})` / `kb.setSkin(null)` | Оформление на ходу (формат — как `skin`) / снять |
| `kb.captureTouches(true/false)` | Слой мода ловит касания (событие `touch`), клавиши в это время не нажимаются — для игр и рисования пальцем. В углу клавиатуры при этом всегда есть ✕: он снимает перехват, а мод получает `touch` с `type: "cancel"` |

Элементы `kb.overlay`:

| Элемент | Поля |
|---|---|
| `{ type: "rect" }` | `x`, `y`, `w`, `h`, `color`, `radius`, `stroke` (толщина контура; 0 — заливка) |
| `{ type: "circle" }` | `x`, `y` (левый верхний угол), `r` или `w`/`h`, `color`, `stroke` |
| `{ type: "line" }` / `{ type: "path" }` | `points: [[x, y], …]`, `color`, `width`, `closed`, `fill` |
| `{ type: "text" }` | `x`, `y`, `text`, `size`, `color`, `bold`, `center` |
| `{ type: "pixels" }` | Пиксель-арт: `x`, `y`, `scale` (размер пикселя), `rows: ["..##..", …]`, `palette: { "#": "#D97757" }`, `flipX` |
| `{ type: "image" }` | `x`, `y`, `w`, `h`, `src` (`data:image/…` или `kb.asset("pic.png")`), `flipX` |

У всех элементов есть `alpha` (0..1) и `rotation` (градусы).

Пример: персонаж ходит от клавиши к клавише и «нажимает» их.

```js
/* @plugin
{ "id": "my.walker", "name": "Питомец", "icon": "🐾" }
*/
var SPRITE = ["..####..", ".######.", "##.##.##", "########", ".#.##.#.", "#.#..#.#"];
var x = 20, y = 20, target = null, flip = false;
kb.on("frame", function (dt) {
  if (!target) {
    var keys = kb.keys().filter(function (k) { return k.type === "character"; });
    if (!keys.length) return;
    target = keys[Math.floor(Math.random() * keys.length)];
  }
  var dx = target.cx - x, dy = target.cy - 12 - y, d = Math.sqrt(dx * dx + dy * dy);
  var step = 0.12 * dt;                       // скорость: 120 dp в секунду
  if (d <= step) {
    kb.pressKey(target.label, { type: false }); // только анимация — в поле ничего не вводится
    target = null;
  } else { x += dx / d * step; y += dy / d * step; flip = dx < 0; }
  kb.overlay([{ type: "pixels", x: x - 12, y: y - 9, scale: 3, rows: SPRITE, palette: { "#": "#D97757" }, flipX: flip }]);
});
```

## Эффекты: частицы, шейдеры, наклон клавиш

Скрипт работает на интерпретаторе и не успевает считать тысячи объектов за кадр, поэтому тяжёлое считает сама клавиатура:
мод только задаёт параметры. `kb.supports(имя)` проверяет возможность: `shader`, `postEffect`, `labelBlur`, `sensors`, `microphone`.

### Частицы

До 20 000 частиц (жидкость — до 3 000), до 8 систем у мода. Они отскакивают от клавиш и реагируют на палец и наклон сами,
без единого вызова скрипта, поэтому для этого не нужны разрешения.

| Функция | Описание |
|---|---|
| `kb.particles.set(имя, настройки)` | Создать систему или поменять настройки на ходу |
| `kb.particles.emit(имя, { count, x, y, w, h, speed, angle, spread })` | Выбросить частицы из точки или прямоугольника; углы в градусах, 0 — вправо |
| `kb.particles.clear(имя)` / `remove(имя)` / `count(имя)` | Убрать частицы / всю систему / узнать, сколько их |

| Настройка | Описание |
|---|---|
| `max`, `size`, `color` или `colors` (до 8) | Сколько, размер в dp, цвета |
| `layer` | `background` (под клавишами, по умолчанию) или `overlay` |
| `gravity` | `[x, y]` или `y`, dp/с² |
| `drag`, `bounce`, `life`, `fade` | Сопротивление, упругость 0..1, время жизни в секундах (0 — вечно), угасание |
| `bounds` | Края клавиатуры: `bounce`, `kill`, `wrap` |
| `collideKeys` | Отскакивать от клавиш |
| `tilt` | Сила тяжести от наклона телефона, dp/с² |
| `touchForce`, `touchRadius` | Палец толкает (больше 0) или притягивает (меньше 0) |
| `emitOnTouch` | Сколько частиц рождается под пальцем при каждом движении |
| `rate`, `rateX`, `rateY`, `rateW`, `rateH`, `speed` | Постоянный поток из прямоугольника, частиц в секунду |
| `liquid`, `liquidRadius`, `stiffness`, `viscosity` | Жидкость: частицы расталкивают друг друга и текут вместе. Это быстрое приближение, а не решение уравнений Навье — Стокса |

```js
kb.particles.set("вода", { max: 1500, liquid: true, size: 7, color: "#664FC3F7", gravity: 500, tilt: 900,
  collideKeys: true, touchForce: 6000, bounce: 0.2 });
kb.particles.emit("вода", { count: 1500, x: 0, y: 0, w: 360, h: 60 });
```

### Шейдеры (Android 13+)

Язык — AGSL: почти GLSL, но вход — `half4 main(float2 fragCoord)`, типы `float2`, `half4`. На старых Android шейдер не
рисуется. Ошибка сборки попадает в журнал мода, текст можно получить из `kb.shaderError(src)`.

Элемент `kb.overlay` и `kb.background`: `{ type: "shader", src, x, y, w, h, uniforms: { ... }, alpha, rotation }`.
Без `w` и `h` он занимает всю клавиатуру. Анимируется сам, вызывать `kb.overlay` каждый кадр не нужно.
`kb.postEffect({ src, uniforms })` применяет шейдер ко всему изображению клавиатуры: искажения, голограмма, преломление.
Изображение приходит как `uniform shader content`. `kb.postEffect(null)` снимает эффект. Нажатия клавиш искажение не сдвигает.

Значения `uniforms`: число, массив чисел (`float2`..`float4`) или цвет `"#RRGGBB"` (в `float4`).
Сами заполняются, если объявлены: `float iTime`, `float2 iResolution` (px), `float3 iTouch` (x, y в px, нажато 0/1),
`float2 iTilt`, `float iAudio` (только у мода с *microphone*, когда он включён).

```js
kb.postEffect({ src: `
  uniform shader content; uniform float iTime;
  half4 main(float2 p) {
    p.x += sin(p.y * 0.04 + iTime * 3.0) * 4.0;
    return content.eval(p);
  }` });
```

### Наклон клавиш и подписи

`kb.keyEffects({ селектор: стиль })` задаёт вид клавиш, `kb.keyEffects(null)` снимает. Селектор: подпись (`a`, `space`, `enter`),
`letter`, `func` или `*`; важнее самый точный. Подходит и для вызова каждый кадр.
`kb.keyboardEffect(стиль)` наклоняет всю клавиатуру, `null` снимает.

| Поле | Описание |
|---|---|
| `rotationX`, `rotationY`, `rotationZ` | Поворот в градусах; X и Y до ±80 |
| `scale`, `x`, `y`, `alpha` | Масштаб, сдвиг в dp, прозрачность |
| `camera` | Расстояние камеры: меньше — сильнее перспектива (по умолчанию 8) |
| `labelAlpha`, `labelBlur`, `labelX`, `labelY`, `labelScale` | Только подпись (буквы, не значки). `labelBlur` — Android 12+, остальное везде |

```js
// параллакс: сетка наклоняется вслед за телефоном (нужно "sensors")
kb.on("tilt", function (t) { kb.keyboardEffect({ rotationY: t.x * 12, rotationX: -t.y * 12, camera: 20 }); });
// «испарение» буквы после нажатия: labelAlpha, labelBlur и labelY плавно меняются в событии frame
```

Клавиши нажимаются там, где они нарисованы; при сильном наклоне всей клавиатуры это заметно.

### Датчики, микрофон, приложение

| Функция | Описание |
|---|---|
| `kb.tilt()` | `{ x, y, z }`. *sensors* |
| `kb.audio.start()` / `stop()` / `level()` | Включить и выключить звук (`start` вернёт `false`, если у клавиатуры нет доступа к микрофону) / громкость 0..1. *microphone* |
| `kb.appInfo()` | `{ package, name, color }` текущего приложения. *app_info* |

Скорость печати считается в обычном скрипте: замеряйте время между событиями `press`.

## Данные, сеть, таймеры

| Функция | Описание |
|---|---|
| `kb.storage.get(key, def)` / `set(key, value)` / `remove(key)` / `keys()` / `clear()` | Хранилище мода (JSON, переживает перезапуск) |
| `kb.asset(path)` | Файл из .zip: картинка → `data:` URI, остальное → текст |
| `kb.http({ url, method, headers, body }, function (err, res) {})` | Только `https://`; `res = { status, ok, text, json }`. *network* |
| `kb.translate(text, to, function (err, result) {})` | Перевод с автоопределением языка. *network* |
| `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval` | Таймеры (до 64 одновременно) |
| `console.log`, `kb.log` | Запись в журнал мода |
| `kb.http({ …, responseType: "base64" }, cb)` | Ответ файлом: `res.base64` (до 10 МБ) — например, картинка для `kb.commitImage` |
| `kb.files.write(name, text)` / `append` / `read(name)` / `writeBase64` / `readBase64` / `exists` / `delete` / `list()` | Файлы мода: своя папка, имена `A-Z a-z 0-9 . _ -`, всего до 32 МБ |

Запросов `kb.http` — до 60 в минуту.

## Промисы

В песочнице есть `Promise` и `.then()`; `async`/`await` и параметры по умолчанию движок не поддерживает. Готовые обёртки:

| Функция | Что возвращает |
|---|---|
| `kb.fetch(url, { method, headers, body, responseType })` | Промис с ответом `kb.http` (*network*) |
| `kb.ask(prompt, text)` | Промис с ответом ИИ (*ai*) |
| `kb.translateAsync(text, to)` | Промис с переводом (*network*) |
| `kb.input({ title, hint, value })` | Промис с введённым текстом (`null` — закрыли) |
| `kb.sleep(ms)` | Промис, который выполнится через ms |

```js
kb.fetch("https://api.github.com/zen").then(function (r) { kb.commit(r.text); }, function (e) { kb.toast(e.message); });
```

## Звук, вибрация, голос

| Функция | Описание |
|---|---|
| `kb.tone(hz, ms, { volume, wave })` | Синтезированный звук; `wave`: `sine`, `square`, `saw`, `triangle`, `noise` |
| `kb.playAudio(src, { volume, loop })` / `kb.stopAudio(id)` | Звук из `data:audio/…` или файла .zip (до 8 МБ); без `id` — остановить все |
| `kb.sound(name, volume)` | Системный звук клавиш: `standard`, `space`, `delete`, `enter`, `click` |
| `kb.haptic(kind)` / `kb.haptic([мс, мс, …])` | Вибрация: `tick`, `click`, `heavy`, `double` или свой узор (пауза, вибрация, пауза…; до 6 с) |
| `kb.speak(text, { lang, rate, pitch })` / `kb.stopSpeaking()` | Прочитать вслух системным синтезатором речи |

## ИИ, ссылки, приложения

| Функция | Описание |
|---|---|
| `kb.ai({ prompt, text }, function (err, result) {})` | Запрос к ИИ, который настроен у пользователя (Gemini, Claude, OpenAI, Groq); до 10 в минуту. *ai* |
| `kb.openUrl(url)` | Открыть `https:`, `http:`, `mailto:`, `tel:`, `sms:`, `geo:`, `market:`, `tg:`, `whatsapp:`. *launch* |
| `kb.share(text)` | Окно «Поделиться». *launch* |
| `kb.launchApp(package)` | Запустить приложение по имени пакета. *launch* |

## Словарь, эмодзи, устройство, другие моды

| Функция | Описание |
|---|---|
| `kb.dictionary.predict(word, previous, lang)` | Подсказки клавиатуры для слова |
| `kb.dictionary.correct(word, lang)` | Автоисправление слова или `null` |
| `kb.dictionary.learn(word, lang)` / `forget(word, lang)` | Выучить / забыть слово |
| `kb.dictionary.nextLetters(prefix, lang)` | Вероятные следующие буквы: `{ "а": 0.3, … }` |
| `kb.emoji.search(query, limit)` / `kb.emoji.recent()` | Поиск эмодзи (`[{ emoji, name }]`) / недавние |
| `kb.device()` | `{ sdk, android, manufacturer, model, dark, landscape, screenWidth, screenHeight, locale, battery, charging, powerSave, appVersion, time, timezone }` |
| `kb.mods()` | Включённые моды: `[{ id, name, version }]` |
| `kb.broadcast(name, data)` | Сообщение другим модам (событие `message`) |
| `kb.util.sha256(text)` / `sha1` / `md5` | Хэш строкой hex |
| `kb.util.base64Encode` / `base64Decode` / `urlEncode` / `urlDecode` / `uuid()` / `now()` | Полезное |
| `kb.util.formatDate(ms, "dd.MM.yyyy HH:mm")` | Дата в своём формате |
| `kb.util.transform(name, text, { prefix, suffix, pattern, replacement })` | Любое преобразование из `transform`: `upper`, `translit`, `layout_swap`, `calc`, `bold`… |
| `atob` / `btoa` | Base64, как в браузере |

## Настройки мода

```json
"config": [
  { "id": "greeting", "title": "Приветствие", "type": "text", "default": "Привет" },
  { "id": "loud", "title": "Со звуком", "type": "toggle", "default": true },
  { "id": "speed", "title": "Скорость", "type": "slider", "min": 1, "max": 10, "step": 1, "default": 5 },
  { "id": "style", "title": "Стиль", "type": "choice", "default": "cat",
    "choices": [{ "id": "cat", "title": "Кот" }, { "id": "dog", "title": "Пёс" }],
    "description": "Кто бегает по клавишам" }
]
```

Настройки видны в карточке мода (**Настройки → Плагины и моды**). Всего до 300 элементов. В скрипте — `kb.config("loud")`,
`kb.config()` (все сразу), `kb.setConfig("loud", false)`, `kb.setConfigs({ loud: false, speed: 3 })`; изменение приходит
событием `option`. Карточка сразу показывает значения, заданные из скрипта. Значение приводится к типу настройки: `1` в
переключателе — это «включено». Если повторно задать то же значение, события не будет.

| `type` | Что это |
|---|---|
| `toggle`, `slider`, `choice`, `text` | Переключатель, ползунок, выбор, строка |
| `textarea` | Многострочное поле, например для вставки пресета: до 256 КБ, необязательно `hint` |
| `color` | Цвет `#RRGGBB` с образцом |
| `preset` | Кнопка: задаёт сразу все настройки из `values`. Работает, даже если мод ещё не запущен |
| `button` | Кнопка: мод получает событие `option` с её `id` |
| `info` | Просто текст (`title`) |
| `tab` | Новая вкладка: всё дальше попадает в неё, `id` не нужен |
| `section` | Сворачиваемый раздел: `collapsed: true` — свёрнут сначала, `id` не нужен |

У любой настройки есть `showIf`: показывать, только если включена другая (`"fire"`), выключена (`"!fire"`) или равна
значению (`"style=cat"`).

```json
"config": [
  { "type": "tab", "title": "Огонь" },
  { "id": "fire", "title": "Огонь", "type": "toggle" },
  { "type": "section", "title": "Тонкая настройка", "collapsed": true },
  { "id": "heat", "title": "Жар", "type": "slider", "min": 0, "max": 10, "default": 5, "showIf": "fire" },
  { "type": "tab", "title": "Пресеты" },
  { "id": "dev", "type": "preset", "title": "Разработчик", "values": { "fire": true, "heat": 10 } },
  { "id": "import", "title": "Вставить пресет", "type": "textarea", "hint": "{ \"fire\": true }" }
]
```

Чтобы поле `import` работало как пресет, разберите текст в моде:
`kb.on("option", function (e) { if (e.id === "import") kb.setConfigs(JSON.parse(e.value)); });`

Версия API — `kb.apiVersion` (сейчас 4).

## Значки

Везде, где есть `icon` (плагин, действие, кнопка, панель, элемент), можно указать:
- эмодзи или короткий текст: `"🚀"`, `"Aa"`;
- `"@имя"` — встроенный значок: `@layout_swap`, `@globe`, `@backspace`, `@enter`, `@shift`, `@grid`, `@translate`, `@search`,
  `@star`, `@heart`, `@settings`, `@mic`, `@clipboard`, `@copy`, `@cut`, `@undo`, `@redo`, `@calc`, `@text`, `@code`, `@link`,
  `@time`, `@date`, `@music`, `@image`, `@bolt`, `@magic`, `@note`, `@list`, `@check`, `@close`, `@refresh`, `@send`, `@case`,
  `@select_all`, `@lock`, `@emoji`, `@gif`, `@games`, `@dice`, `@palette`, `@extension`, `@keyboard`, `@language`, `@add`,
  `@delete`, `@edit`, `@share`, `@home`, `@person`, `@mail`;
- картинку `"data:image/png;base64,…"` (добавьте `#tint` в конце, чтобы перекрасить её в цвет темы).

## icons: замена иконок клавиатуры

```json
"icons": { "mic": "🎙️", "globe": "🌍", "backspace": "icons/back.png", "emoji_cat_0": "🙂" }
```

Слоты: `menu`, `back`, `mic`, `plugins`, `translate`, `gif`, `clipboard`, `emoji`, `one_handed`, `incognito`, `settings`,
`hide`, `layout_swap`, `globe`, `emoji_key`, `shift`, `caps`, `backspace`, `enter`, `go`, `send`, `search`, `done`,
`emoji_recent`, `emoji_cat_0` … `emoji_cat_6`. Значение — эмодзи/текст, `data:image/…` или путь к картинке в .zip.
Иконки, выбранные пользователем в **Настройки → Иконки и эмодзи**, важнее иконок из плагинов.

## languages: свой язык

```json
"languages": [{
  "id": "cv", "name": "Чӑвашла", "title": "Чувашский", "label": "CV", "locale": "cv-RU",
  "rows": ["йцукенгшщзхӑ", "фывапролджэӗ", "ячсмитьбюҫӳ"],
  "variants": { "е": ["ё", "ӗ"] }
}]
```

Shift и ⌫ добавляются в последний ряд сами. Необязательные поля: `shift` (`{ "क": "ख" }` — что вводит клавиша с Shift
у письменностей без заглавных), `dictionary` (код частотного словаря FrequencyWords, если он есть), `rtl`, `autoCorrect`.
Язык появляется в **Настройки → Языки и словари**.

## dictionary: слова для подсказок

```json
"dictionary": { "ru": ["кринж", "имхо", "лол"] }
```

Слова из словаря плагина не исправляются автоисправлением и появляются в подсказках.

## actions: кнопки на панели плагинов (до 64)

Общие поля действия: `title` (обязательно), `icon`, `type`, `pinned`.
`"pinned": true` выносит кнопку прямо на панель инструментов клавиатуры (рядом с 🧩).

### `insert`: вставить текст по шаблону

```json
{ "title": "Подпись", "type": "insert", "text": "С уважением,{newline}Иван" }
```

### `transform`: преобразовать текст

Что преобразуется, задаёт `scope`:
- `auto` (по умолчанию): выделение, а если его нет — последнее слово перед курсором;
- `word`: последнее слово;
- `text`: весь текст поля;
- `selection`: только выделение.

| `transform` | Что делает |
|---|---|
| `upper` / `lower` | ВЕРХНИЙ / нижний регистр |
| `title` / `sentence` | Каждое Слово / Как в предложении |
| `reverse` | Задом наперёд |
| `mock` | сАрКаЗм |
| `wrap` | Обернуть: поля `prefix`, `suffix` |
| `regex` | Замена: поля `pattern`, `replacement` (группы `$1`) |
| `trim` | Убрать лишние пробелы |
| `sort_lines` / `unique_lines` | Сортировать строки / убрать повторы |
| `base64_encode` / `base64_decode` | Base64 |
| `url_encode` / `url_decode` | URL-кодирование |
| `translit` | Кириллица → латиница |
| `layout_swap` | Исправить раскладку: `ghbdtn` → `привет` |
| `strike` | З̶а̶ч̶е̶р̶к̶н̶у̶т̶ь̶ |
| `spaced` | р а з р я д к а |
| `calc` | `2+2*3` → `2+2*3 = 8` (`+ - * / ^ %`, скобки, `×` `÷`, запятая) |
| `camel` / `snake` / `kebab` | `мой новый файл` → `мойНовыйФайл` / `мой_новый_файл` / `мой-новый-файл` |
| `bold` / `italic` | 𝗕𝗼𝗹𝗱 / 𝘐𝘵𝘢𝘭𝘪𝘤 — только латиница и цифры (символы Unicode) |
| `superscript` | `x2` → `x²` (цифры, `+ - = ( ) n i`) |

### `key`: системная клавиша или команда

`tab`, `enter`, `left`, `right`, `up`, `down`, `home`, `end`, `select_all`, `copy`, `cut`, `paste`,
`undo`, `redo`, `delete_word`, `escape`.

```json
{ "title": "Выделить всё", "type": "key", "key": "select_all" }
```

### `http`: запрос к своему серверу

Нужно `"permissions": ["network"]`. Разрешены только `https://` адреса. При установке такого плагина
клавиатура предупреждает, что он отправляет текст в сеть. Запрос уходит только по нажатию кнопки.

| Поле | Описание |
|---|---|
| `url` | Шаблон адреса; подстановки в нём URL-кодируются |
| `method` | `GET` (по умолчанию) или `POST` |
| `body` | Тело для `POST` (шаблон) |
| `headers` | Объект заголовков (значения — шаблоны) |
| `jsonPath` | Путь к ответу в JSON: `data.0.text`. Пусто — весь ответ как текст |
| `output` | `replace` (заменить исходный текст, по умолчанию) или `insert` |
| `scope` | Как у `transform` |

```json
{
  "title": "В эмодзи", "type": "http",
  "url": "https://example.com/api/emojify?q={text}",
  "jsonPath": "result"
}
```

## Шаблоны

Работают в `insert`, `shortcuts`, `url`, `body` и `headers`:

| Подстановка | Значение |
|---|---|
| `{date}`, `{time}`, `{datetime}` | Текущие дата и время |
| `{date:yyyy-MM-dd}` | Дата в своём формате (`SimpleDateFormat`) |
| `{text}` / `{selection}` | Выделенный текст (в `http` — исходный текст по `scope`) |
| `{clipboard}` | Последнее скопированное |
| `{random:да\|нет\|может быть}` | Случайный вариант |
| `{uuid}` | Случайный UUID |
| `{newline}` | Перенос строки |

## shortcuts: автозамена по пробелу (до 500)

```json
"shortcuts": { "спс": "спасибо", ":date": "{date}", "<3": "❤️" }
```

Регистр триггера не важен. Сокращение из одних букв ещё и появляется в строке подсказок, пока вы его набираете.

## symbols: наборы символов (до 20 наборов по 2000 элементов)

```json
"symbols": [
  { "title": "Каомодзи", "icon": "ツ", "items": ["(◕‿◕)", "¯\\_(ツ)_/¯"] }
]
```

Каждый набор открывается отдельной вкладкой на панели плагинов.

## theme: тема

Применяется кнопкой «Применить оформление» в карточке плагина и записывается в настройки.

```json
"theme": {
  "seed": "#FF7A3D",
  "mode": "DARK",
  "shape": "FLOWER_6",
  "keyOpacity": 0.9,
  "colors": {
    "background": "#1A1110", "key": "#2B1F1C", "keyText": "#FFE9E2",
    "func": "#3B2A25", "funcText": "#FFDBCF", "accent": "#FF7A3D", "accentText": "#2B1100"
  }
}
```

- `mode`: `SYSTEM`, `LIGHT`, `DARK` или `AMOLED`.
- `shape`: `COOKIE_12`, `WAVY_8`, `FLOWER_6`, `CLOVER_4` или `CIRCLE`.
- Цвета пишутся как `#RRGGBB` или `#AARRGGBB`. Если цвет не указан, он берётся из темы.

Кнопка в карточке плагина называется **«Применить оформление»**: она применяет `theme`, `settings` и включает `skin`
одним нажатием. «Снять оформление» возвращает обычный вид Material 3 Expressive.

## skin: как рисовать каждую клавишу

`skin` не трогает настройки — он рисует клавиатуру поверх них, пока включён. Одновременно активен один skin.

```json
"skin": {
  "keyboard": {
    "background": { "colors": ["#1B1F4B", "#3A1C71"], "angle": 120 },
    "orbs": [ { "color": "#FF5F9E", "x": 0.15, "y": 0.4, "r": 0.3 } ],
    "corner": 32,
    "font": "mono",
    "trail": "#CCFFFFFF"
  },
  "colors": { "accent": "#8AB4FF", "keyText": "#FFFFFF" },
  "keys": {
    "*":      { "material": "glass", "press": "jelly", "text": "#FFFFFF" },
    "func":   { "glass": { "frost": 0.4 } },
    "enter":  { "background": { "colors": ["#8AB4FF", "#B388FF"], "angle": 135 }, "shape": "pill" },
    "space":  { "label": "␣" },
    "key:я":  { "material": "solid", "background": "#FF7A3D", "glow": "#FF7A3D" }
  },
  "popup": { "material": "glass", "pressedBackground": "#8AB4FF" },
  "chips": { "material": "glass", "text": "#FFFFFF" }
}
```

### keyboard — фон и общее

| Поле | Описание |
|---|---|
| `background` | Цвет или градиент фона клавиатуры |
| `orbs` | До 12 мягких цветных пятен: `color`, `x`/`y` (0..1 от размера клавиатуры), `r` (радиус, доля) |
| `corner` | Скругление верхних углов клавиатуры, dp |
| `font` | `serif`, `mono`, `sans`, `cursive`, `pixel` (пиксельный Pixelify Sans) — шрифт подписей |
| `trail` | Цвет следа при печати свайпом |

`colors` — те же ключи, что у `theme.colors`, но действуют только пока включён skin.

### keys — стили клавиш

Селекторы применяются по порядку, следующий перекрывает предыдущий:
`*` → `letter` (буквы и символы) или `func` (shift, ⌫, ?123, 🌐, 😀) → `digit` → `shift` / `backspace` / `mode` /
`globe` / `emoji` / `space` / `enter` → `key:<буква>` (например `key:a`, `key:я`).

| Поле | Значения |
|---|---|
| `material` | `solid` (по умолчанию), `glass` — жидкое стекло, `outline` — контур, `neumorph` — выпуклая, `bevel` — объёмная пиксельная кнопка (светлая кромка сверху слева, тёмная снизу справа, как в Minecraft), `none` — без фона |
| `background` / `pressedBackground` | Цвет или градиент в покое / при нажатии |
| `text` / `pressedText` | Цвет подписи |
| `textGlow` | Свечение подписи |
| `border`, `borderWidth` | Цвет/градиент и толщина контура, dp |
| `borderStyle` | `solid`, `dots` — контур из точек (как у Nothing), `dashed` — пунктир |
| `labelEffect` | `dots` — буквы из точек, как на точечной матрице; `none` — обычные |
| `textShadow` | Жёсткая тень подписи без размытия (как текст в Minecraft) |
| `bevel` | Толщина объёмной кромки у `material: bevel`, dp |
| `shape` | `rounded`, `pill`, `cut` (срезанные углы), `squircle`, `wavy` |
| `radius` / `pressedRadius` | Скругление в покое / при нажатии, dp (форма «перетекает») |
| `shadow`, `shadowY`, `shadowColor` | Мягкая тень: размытие и сдвиг в dp (Android 9+) |
| `glow` | Свечение вокруг клавиши: у `outline` горит всегда, у остальных — при нажатии |
| `press` | `scale`, `jelly` (желе с покачиванием), `bounce` (увеличение), `sink` (вдавливание), `none` |
| `pressScale` | Масштаб при нажатии для `scale`/`bounce`, 0.6..1.3 |
| `inset` | Отступ внутрь ячейки, dp |
| `label` | Своя подпись (для служебных клавиш вместо значка) |
| `fontScale`, `fontWeight`, `italic` | Размер (множитель), насыщенность 100..900, курсив |
| `hideHint` | Скрыть маленькую подсказку в углу |
| `glass` | Настройки стекла, см. ниже |

### glass — жидкое стекло

Стекло видит фон клавиатуры (градиент, `orbs`, фоновую картинку) и преломляет его по краям.
На Android 13+ это настоящее преломление шейдером с радужной каймой, на старых версиях — увеличенная размытая подложка.

| Поле | По умолчанию | Описание |
|---|---|---|
| `blur` | 14 | Размытие фона, dp |
| `refraction` | 0.6 | Сила преломления по краям, 0..1 |
| `dispersion` | 0.25 | Радужная кайма, 0..1 |
| `highlight` | 0.7 | Блик сверху |
| `rim` | 0.7 | Светлый ободок |
| `frost` | 0 | Матовость: молочная дымка — клавиша выглядит размытее фона |
| `grain` | 0 | Зерно матового стекла |
| `tint` | — | Цвет тонировки (лучше полупрозрачный, `#33FFFFFF`) |

Если задан `background`, он рисуется поверх стекла как тонировка — используйте полупрозрачные цвета.

### popup и chips

`popup` — всплывающая буква и варианты: `background`, `pressedBackground` (выбранный вариант), `text`, `pressedText`,
`material: glass`, `border`, `radius` (с ним вместо волнистой «печеньки» рисуется скруглённый прямоугольник).
`chips` — подсказки и панель инструментов: `background`, `pressedBackground` (средняя подсказка),
`text`, `pressedText`, `border`, `borderWidth`, `material: glass`; `material: none` — подсказки просто текстом
через тонкие разделители, как в Gboard и iOS.

### buttons — кнопки по краям строки подсказок

Меню и микрофон. Тот же формат, что у клавиш: `material` (`none` — только значок, `solid`, `glass`, `outline`,
`neumorph`), `background`, `pressedBackground`, `text` (цвет значка), `border`, `glow`, `shadow`. Без `buttons`
кнопки рисуются волнистыми «печеньками» в цвет акцента.

### panel — подложка панелей

Эмодзи, буфер обмена, голосовой ввод, переводчик, GIF и вкладки плагинов. Формат как у клавиш: `material`
(`solid`, `glass`, `outline`, `neumorph`), `background`, `border`, `borderWidth`, `shadow`, `shadowColor`, `radius`, `inset`.
Без `panel` панели рисуются тональной подложкой со скруглением 24.

### Размытие под клавиатурой

`keyboard.windowBlur` — радиус (dp), с которым клавиатура размывает приложение под собой, `keyboard.blurTint` — дымка
поверх размытия (полупрозрачный цвет). Работает на Android 12+, если система разрешает размытие окон (не в режиме
энергосбережения); иначе рисуется обычный фон `keyboard.background`. Стеклянные клавиши при размытии показывают
матовое стекло с бликом и ободком.

### dark — тёмная тема

Объект с теми же разделами (`keyboard`, `colors`, `keys`, `popup`, `chips`, `buttons`), который накладывается
поверх оформления, когда включена тёмная тема. Указывайте только то, что отличается:

```json
"colors": { "background": "#D1D4DA", "key": "#FFFFFF", "keyText": "#1C1C1E" },
"dark": {
  "colors": { "background": "#1C1C1E", "key": "#4A4A4E", "keyText": "#F2F2F7" },
  "keys": { "func": { "background": "#2E2E31" } }
}
```

### Градиенты и узоры

Цвет — строка `"#RRGGBB"`. Градиент — массив `["#..", "#.."]` (сверху вниз) или объект
`{ "colors": [...], "angle": 135, "type": "radial" }`. Угол: 0 — слева направо, 90 — сверху вниз.

Узор — объект с `pattern`: `{ "pattern": "pixels", "colors": ["#6F6F6F", "#7A7A7A", "#646464"], "cell": 3, "seed": 5 }`.
`pixels` — случайные квадратные клетки из цветов (первый встречается чаще всего: камень, земля), `planks` — доски с
щелями и прожилками, `dots` — точки `colors[1]` на фоне `colors[0]` (точечная сетка). `cell` — размер клетки, dp;
`seed` — другой рисунок того же узора. Узор подходит для фона клавиатуры, клавиш, кнопок и панелей.

### options — настройки темы

У каждой темы могут быть свои настройки: они появляются в «Темы» → «Настройки темы». Выбор и переключатели —
заплатки JSON поверх оформления (объекты сливаются, остальное заменяется), ползунки меняют числа:

```json
"options": [
  { "id": "texture", "title": "Текстура", "type": "choice", "default": "stone",
    "choices": [
      { "id": "stone", "title": "Камень" },
      { "id": "oak", "title": "Доски", "patch": { "keys": { "*": { "background": { "pattern": "planks", "colors": ["#A2834F", "#B8945F"] } } } } }
    ] },
  { "id": "dots", "title": "Буквы из точек", "type": "toggle", "default": true,
    "off": { "keys": { "*": { "labelEffect": "none" } } } },
  { "id": "frost", "title": "Матовость", "type": "slider", "knob": "glass.frost", "min": 0, "max": 1, "default": 0.4 }
]
```

Ползунки (`knob`): `radiusScale`, `shadowScale`, `glowScale`, `borderScale`, `keyAlpha`, `windowBlur`,
`glass.frost`, `glass.grain`, `glass.refraction`, `glass.highlight`, `glass.rim`, `glass.dispersion`, `glass.blur`.
Кроме своих настроек, у любой темы есть общие: скругление, тени, прозрачность, стекло, анимация нажатия, шрифт и
«Красить в мой акцентный цвет». Если в настройках выбран свой акцент, цвета темы близкие к её акценту (`colors.accent`)
перекрашиваются в него.

## settings: любые настройки

Применяются кнопкой «Применить оформление». Ключи — как в резервной копии настроек:

```json
"settings": { "key_height_dp": 58, "show_number_row": true, "theme_mode": "DARK", "color_accent": "#FF7A3D", "glide_typing": true }
```

Числа с плавающей точкой: `key_height_dp`, `corner_radius_dp`, `key_spacing_dp`, `row_spacing_dp`, `bottom_offset_dp`,
`side_padding_dp`, `key_label_scale`, `key_opacity`, `press_animation`, `long_press_ms`, `vibration_ms`,
`vibration_amplitude`, `bg_dim`, `bg_blur_dp`, `one_handed_width`, `wave_amplitude_percent`, `sound_volume`.
Целые и цвета (`"#hex"`): `color_background`, `color_key`, `color_key_text`, `color_func`, `color_func_text`,
`color_accent`, `color_accent_text`, `seed_color`, `wave_count`, `font_weight`, `clipboard_size`.
Да/нет: `show_number_row`, `show_key_borders`, `key_preview`, `show_hints`, `show_suggestions`, `show_mic_button`,
`show_emoji_key`, `show_globe_key`, `panel_animations`, `auto_capitalize`, `auto_correct_enabled`,
`double_space_period`, `haptics_enabled`, `vibrate_on_release`, `strong_special_keys`, `sound_enabled`,
`backspace_swipe`, `clipboard_history`, `instant_input`, `glide_typing`, `glide_trail`, `swipe_up_hint`,
`space_long_press`, `auto_pair`, `incognito`.
Строки: `theme_mode`, `accent_shape_preset`, `haptic_mode`, `font_style`, `color_source`, `one_handed`, `space_swipe`.

## variants: что появляется при удержании

```json
"variants": { "е": ["ё", "е", "є"], ".": ["…", "!", "?"] }
```

## layouts: своя раскладка

Ключи: код любого языка (`ru`, `en`, `de`, `uk`…), `symbols`, `symbols2`. Если в рядах нет `{space}`, плагин заменяет
только верхние ряды, а нижний ряд (?123, 🌐, «,», пробел, «.», ввод) остаётся стандартным. С `{space}` заменяется вся
клавиатура.

```json
"layouts": {
  "en": [
    ["q", "w", "f", "p", "g", "j", "l", "u", "y", ";"],
    ["a", "r", "s", "t", "d", "h", "n", "e", "i", "o"],
    ["{shift}", "z", "x", "c", "v", "b", "k", "m", "{backspace}"]
  ]
}
```

Служебные клавиши: `{shift}`, `{backspace}`, `{space}`, `{enter}`, `{symbols}` (?123), `{abc}`, `{page}` (=\<),
`{globe}`, `{emoji}`, `{gap}`. Ширина — через двоеточие: `{shift:1.5}`, `{gap:0.5}`.
Своя клавиша — объект: `{ "label": "→", "output": "->", "weight": 1.2, "variants": ["⇒", "⟶"] }`.
Клавиша мода — `{ "label": "λ", "action": "имя" }`: вызывает команду, зарегистрированную `kb.addCommand("имя", fn)`.
Высота ряда — объект `{ "rowHeight": 0.8 }` в начале ряда.
