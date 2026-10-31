/* @plugin
{
  "id": "builtin.smart-quotes",
  "name": "Ёлочки «»",
  "version": "1.1",
  "author": "Expressive Keyboard",
  "description": "Мод: кавычка \" сама становится « или » — в зависимости от того, открываете вы цитату или закрываете. Пример события key.",
  "icon": "«»",
  "permissions": ["read_text"],
  "enabledByDefault": false
}
*/

kb.on("key", function (e) {
  if (e.text !== "\"") return;
  var before = kb.textBefore(1);
  // Закрывающая — после буквы, цифры, конца фразы или уже открытой кавычки:
  // «слово" → «слово», «" → «», (текст)" → (текст)». Иначе открывающая.
  // Раньше « и „ считались началом фразы, и две кавычки подряд давали «« вместо «».
  var letter = before !== "" && before.toLowerCase() !== before.toUpperCase();
  var closing = letter || /[0-9.,!?;:…)\]]$/.test(before) || before === "«" || before === "„" || before === "“";
  return closing ? "»" : "«";
});
