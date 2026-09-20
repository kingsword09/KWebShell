'use strict';
// Build-time tooling only. The TypeScript version is the repository's pinned compiler.
const fs = require('node:fs');
const path = require('node:path');
const { builtinModules } = require('node:module');
const tsRoot = process.argv[2];
const metadata = require(path.join(tsRoot, 'package.json'));
if (metadata.version !== '6.0.2') throw new Error('TypeScript 6.0.2 is required by migration inventory.');
const ts = require(tsRoot);
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const files = new Map(input.files.map(f => [path.resolve(input.root, f.path), f.text]));
const sourceNames = [...files.keys()].filter(f => /\.(?:[cm]?[jt]sx?)$/.test(f));
const options = { allowJs: true, noLib: true, target: ts.ScriptTarget.ESNext, module: ts.ModuleKind.CommonJS, moduleResolution: ts.ModuleResolutionKind.Node10 };
const host = ts.createCompilerHost(options);
host.readFile = name => files.get(path.resolve(name));
host.fileExists = name => files.has(path.resolve(name));
host.directoryExists = name => [...files.keys()].some(f => f.startsWith(path.resolve(name) + path.sep));
host.getSourceFile = (name, languageVersion) => {
  const text = host.readFile(name);
  return text === undefined ? undefined : ts.createSourceFile(name, text, languageVersion, true);
};
const program = ts.createProgram(sourceNames, options, host);
const checker = program.getTypeChecker();
const facts = [];
const builtin = new Set(builtinModules.map(n => n.replace(/^node:/, '')));
const electronNames = new Set(['app', 'ipcMain', 'ipcRenderer', 'contextBridge', 'BrowserWindow', 'webContents', 'session', 'protocol']);
function emit(sf, node, kind, expression, extra = {}) {
  const pos = sf.getLineAndCharacterOfPosition(node.getStart(sf));
  facts.push({ path: path.relative(input.root, sf.fileName).split(path.sep).join('/'), line: pos.line + 1, column: pos.character + 1, kind, expression, ...extra });
}
function literal(node) { return node && (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) ? node.text : null; }
function unwrap(node) {
  while (node && (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isNonNullExpression(node) || ts.isSatisfiesExpression(node))) node = node.expression;
  return node;
}
function importOwner(node) {
  while (node && !ts.isImportDeclaration(node) && !ts.isExportDeclaration(node) && !ts.isImportEqualsDeclaration(node)) node = node.parent;
  return node;
}
function externalModule(node) {
  const owner = importOwner(node);
  if (!owner) return null;
  return ts.isImportEqualsDeclaration(owner) ? literal(owner.moduleReference.expression) : literal(owner.moduleSpecifier);
}
function moduleBinding(module, name, sourceFile, visited = new Set()) {
  if (module === 'electron') return `electron.${name}`;
  if (!module?.startsWith('.')) return null;
  const resolved = ts.resolveModuleName(module, sourceFile.fileName, options, host).resolvedModule?.resolvedFileName;
  if (!resolved || visited.has(`${resolved}:${name}`)) return null;
  visited.add(`${resolved}:${name}`);
  const target = program.getSourceFile(resolved);
  if (!target) return null;
  for (const statement of target.statements) {
    if (!ts.isExportDeclaration(statement) || !statement.moduleSpecifier) continue;
    if (!statement.exportClause) {
      const value = moduleBinding(literal(statement.moduleSpecifier), name, target, visited);
      if (value) return value;
    } else if (ts.isNamedExports(statement.exportClause)) {
      const spec = statement.exportClause.elements.find(spec => spec.name.text === name);
      if (spec) return moduleBinding(literal(statement.moduleSpecifier), (spec.propertyName ?? spec.name).text, target, visited);
    }
  }
  return null;
}
function binding(node, seen = new Set()) {
  node = unwrap(node);
  if (!node || seen.has(node)) return null;
  seen.add(node);
  if (ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node)) {
    const base = binding(node.expression, seen);
    const prop = ts.isPropertyAccessExpression(node) ? node.name.text : literal(node.argumentExpression);
    return base ? `${base}.${prop ?? '<computed>'}` : null;
  }
  if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'require' && !checker.getSymbolAtLocation(node.expression)) {
    return literal(node.arguments[0]) === 'electron' ? 'electron' : null;
  }
  if (!ts.isIdentifier(node)) return null;
  const symbol = checker.getSymbolAtLocation(node);
  if (!symbol) return electronNames.has(node.text) ? `electron.${node.text}` : null;
  let declarations = symbol.declarations ?? [];
  if (symbol.flags & ts.SymbolFlags.Alias) {
    const target = checker.getAliasedSymbol(symbol);
    declarations = [...declarations, ...(target.declarations ?? [])];
  }
  for (const declaration of declarations) {
    if (ts.isImportSpecifier(declaration) || ts.isExportSpecifier(declaration)) {
      const value = moduleBinding(externalModule(declaration), (declaration.propertyName ?? declaration.name).text, declaration.getSourceFile());
      if (value) return value;
    } else if (ts.isNamespaceImport(declaration) || ts.isImportClause(declaration) || ts.isImportEqualsDeclaration(declaration)) {
      if (externalModule(declaration) === 'electron') return 'electron';
    } else if (ts.isVariableDeclaration(declaration)) {
      const value = binding(declaration.initializer, seen);
      if (value) return value;
    } else if (ts.isBindingElement(declaration)) {
      const value = binding(declaration.parent.parent.initializer, seen);
      const name = declaration.propertyName ?? declaration.name;
      if (value && ts.isIdentifier(name)) return `${value}.${name.text}`;
    }
  }
  return null;
}
function moduleFact(sf, node, module, symbol, reExport = false) {
  if (module === 'electron') emit(sf, node, 'ELECTRON_IMPORT', reExport ? `export electron.${symbol}` : `electron.${symbol}`, { symbol, reExport });
  else if (module?.startsWith('node:') || builtin.has(module) || builtin.has(module?.split('/')[0])) emit(sf, node, 'NODE_IMPORT', module);
  else if (module && !module.startsWith('.') && !module.startsWith('/')) emit(sf, node, 'PACKAGE_DEPENDENCY', module);
  else if (module?.endsWith('.node')) emit(sf, node, 'NATIVE_ADDON', module);
}
for (const fileName of sourceNames) {
  const sf = program.getSourceFile(fileName);
  for (const diagnostic of program.getSyntacticDiagnostics(sf)) {
    const pos = sf.getLineAndCharacterOfPosition(diagnostic.start ?? 0);
    facts.push({ path: path.relative(input.root, fileName).split(path.sep).join('/'), line: pos.line + 1, column: pos.character + 1, kind: 'UNCLASSIFIED', expression: ts.flattenDiagnosticMessageText(diagnostic.messageText, ' ') });
  }
  function visit(node) {
    if (ts.isIdentifier(node) && ['eval', 'Function', 'require'].includes(node.text) && !checker.getSymbolAtLocation(node)) {
      const parent = node.parent;
      const directCall = (ts.isCallExpression(parent) || ts.isNewExpression(parent)) && parent.expression === node;
      const propertyName = (ts.isPropertyAccessExpression(parent) && parent.name === node) || ((ts.isPropertyAssignment(parent) || ts.isMethodDeclaration(parent)) && parent.name === node);
      if (!directCall && !propertyName) emit(sf, node, 'DYNAMIC_EXECUTION', `${node.text} reference escapes static analysis`);
    }
    if (ts.isElementAccessExpression(node) && ts.isIdentifier(node.expression) && ['globalThis', 'window'].includes(node.expression.text) && (!literal(node.argumentExpression) || ['eval', 'Function', 'require'].includes(literal(node.argumentExpression)))) emit(sf, node, 'DYNAMIC_EXECUTION', 'computed global execution');
    if (ts.isImportDeclaration(node)) {
      const module = literal(node.moduleSpecifier);
      const bindings = node.importClause?.namedBindings;
      if (module === 'electron' && bindings && ts.isNamedImports(bindings)) {
        for (const spec of bindings.elements) moduleFact(sf, spec, module, (spec.propertyName ?? spec.name).text);
      } else moduleFact(sf, node, module, '*');
    }
    if (ts.isExportDeclaration(node) && node.moduleSpecifier) {
      const module = literal(node.moduleSpecifier);
      if (node.exportClause && ts.isNamedExports(node.exportClause)) {
        for (const spec of node.exportClause.elements) moduleFact(sf, spec, module, (spec.propertyName ?? spec.name).text, true);
      } else moduleFact(sf, node, module, '*', true);
    }
    if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference)) moduleFact(sf, node, literal(node.moduleReference.expression), '*');
    if (ts.isNewExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'Function' && !checker.getSymbolAtLocation(node.expression)) emit(sf, node, 'DYNAMIC_EXECUTION', 'new Function(...)');
    if (ts.isCallExpression(node)) {
      const callee = unwrap(node.expression);
      const globalCall = ts.isIdentifier(callee) && !checker.getSymbolAtLocation(callee);
      if ((globalCall && ['eval', 'Function'].includes(callee.text)) || (ts.isPropertyAccessExpression(callee) && ['globalThis', 'window'].includes(callee.expression.getText(sf)) && callee.name.text === 'eval')) emit(sf, node, 'DYNAMIC_EXECUTION', 'eval(...)');
      if ((globalCall && callee.text === 'require') || callee.kind === ts.SyntaxKind.ImportKeyword) {
        const module = literal(node.arguments[0]);
        if (module === null) emit(sf, node, 'DYNAMIC_EXECUTION', `${callee.getText(sf)}(<dynamic>)`);
        else {
          const parent = node.parent;
          if (module === 'electron' && ts.isVariableDeclaration(parent) && ts.isObjectBindingPattern(parent.name)) {
            for (const element of parent.name.elements) moduleFact(sf, element, module, (element.propertyName ?? element.name).getText(sf));
          } else if (module === 'electron' && ts.isPropertyAccessExpression(parent)) moduleFact(sf, parent, module, parent.name.text);
          else moduleFact(sf, node, module, '*');
        }
      }
      const target = binding(callee);
      if (target?.startsWith('electron.ipcMain.') || target?.startsWith('electron.ipcRenderer.')) {
        emit(sf, node, 'ELECTRON_CHANNEL', literal(node.arguments[0]) ?? '<dynamic>', { operation: target });
      } else if (target === 'electron.contextBridge.exposeInMainWorld') {
        const value = unwrap(node.arguments[1]);
        const exports = value && ts.isObjectLiteralExpression(value) ? value.properties.map(p => p.name && !ts.isComputedPropertyName(p.name) ? (literal(p.name) ?? p.name.getText(sf)) : '<dynamic>') : ['<dynamic>'];
        emit(sf, node, 'PRELOAD_GLOBAL', literal(node.arguments[0]) ?? '<dynamic>', { exports });
      } else if (target?.startsWith('electron.app.')) {
        emit(sf, node, 'LIFECYCLE_EVENT', `app.${literal(node.arguments[0]) ?? '<dynamic>'}`, { operation: target });
      } else if (target?.startsWith('electron.')) {
        emit(sf, node, 'UNCLASSIFIED', target);
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(sf);
}
for (const [name, text] of files) {
  if (!name.endsWith(`${path.sep}package.json`)) continue;
  const sf = ts.parseJsonText(name, text);
  if (sf.parseDiagnostics.length) {
    emit(sf, sf, 'UNCLASSIFIED', 'Invalid package.json');
    continue;
  }
  const object = sf.statements[0]?.expression;
  if (!object || !ts.isObjectLiteralExpression(object)) { emit(sf, sf, 'UNCLASSIFIED', 'package.json must be an object'); continue; }
  for (const property of object.properties) {
    if (['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies'].includes(literal(property.name))) {
      if (!ts.isObjectLiteralExpression(property.initializer)) { emit(sf, property, 'UNCLASSIFIED', 'Invalid dependency section'); continue; }
      for (const dep of property.initializer.properties) emit(sf, dep.name, 'PACKAGE_DEPENDENCY', literal(dep.name) ?? '<dynamic>');
    }
    if (literal(property.name) === 'gypfile' && property.initializer.kind === ts.SyntaxKind.TrueKeyword) emit(sf, property.name, 'NATIVE_ADDON', 'gypfile');
  }
}
process.stdout.write(JSON.stringify(facts));
