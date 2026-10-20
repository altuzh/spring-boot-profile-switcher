import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const script = readFileSync(new URL('./content.js', import.meta.url), 'utf8');
const manifest = JSON.parse(readFileSync(new URL('./manifest.json', import.meta.url), 'utf8'));
assert.equal(manifest.content_scripts[0].world, 'MAIN');
assert.deepEqual(manifest.content_scripts[0].matches, [
  'https://ui.mesh-java.test.ecp/graphiql/*',
  'https://ui.mesh-java.uat.ecp/graphiql/*'
]);
assert.deepEqual(manifest.host_permissions, [
  'https://ui.mesh-java.test.ecp/*',
  'https://ui.mesh-java.uat.ecp/*'
]);
assert.deepEqual(manifest.permissions, ['declarativeNetRequestWithHostAccess']);
const rules = JSON.parse(readFileSync(new URL('./rules.json', import.meta.url), 'utf8'));
assert.equal(manifest.declarative_net_request.rule_resources[0].enabled, true);
assert.equal(rules.length, 2);
for (const rule of rules) {
  assert.deepEqual(rule.condition.resourceTypes, ['main_frame']);
}
const redirect = url => {
  for (const rule of rules) {
    const regex = new RegExp(rule.condition.regexFilter);
    if (regex.test(url)) {
      return url.replace(regex, rule.action.redirect.regexSubstitution.replace('\\1', '$1'));
    }
  }
  return url;
};
for (const env of ['test', 'uat']) {
  const longLink = `https://ui.mesh-java.${env}.ecp/graphiql/index.html?query=` + 'x'.repeat(20000);
  const redirected = new URL(redirect(longLink));
  assert.equal(redirected.search, '', 'the long query must not be sent to Nginx');
  assert.equal(redirected.hash, '#query=' + 'x'.repeat(20000));
  assert.equal(redirect(`https://ui.mesh-java.${env}.ecp/graphiql/?query=x`),
    `https://ui.mesh-java.${env}.ecp/graphiql/index.html#query=x`);
  assert.equal(redirect(`https://ui.mesh-java.${env}.ecp/graphql?query=x`), `https://ui.mesh-java.${env}.ecp/graphql?query=x`);
}

function page(search, hash = '') {
  const tabs = [{ query: 'query Existing { __typename }', variables: '{"old":true}' }];
  const executions = [];
  let active = 0;
  let notice;
  const location = { hash, pathname: '/graphiql/index.html', search };
  const editor = field => ({ setValue(value) { tabs[active][field] = value; }, focus() {} });
  const queryEditor = { CodeMirror: editor('query') };
  const variablesEditor = { CodeMirror: editor('variables') };
  const addButton = { click() { tabs.push({ query: '', variables: '' }); active++; } };
  const executeButton = { click() { executions.push({ ...tabs[active] }); } };
  const document = {
    querySelector(selector) {
      if (selector === 'button[aria-label="Add tab"]') return addButton;
      if (selector === 'button[aria-label="Execute query (Ctrl-Enter)"]') return executeButton;
      if (selector === '.graphiql-query-editor .CodeMirror') return queryEditor;
      if (selector.startsWith('[aria-label="Variables"]')) return variablesEditor;
      throw new Error(`Unexpected selector: ${selector}`);
    },
    querySelectorAll(selector) {
      assert.equal(selector, '[role="tablist"] [role="tab"]');
      return tabs;
    },
    getElementById() { return notice; },
    createElement() { return { style: {}, setAttribute() {}, remove() { notice = undefined; } }; },
    body: { append(element) { notice = element; } },
  };
  const history = {
    state: null,
    replaceState(_state, _title, url) {
      const updated = new URL(url, 'https://example.test');
      location.search = updated.search;
      location.hash = updated.hash;
    },
  };
  const window = { GraphiQL: { GraphQL: { parse(source) {
    if (source === 'invalid') throw new Error('Parse error');
    const operation = /^\s*(mutation|subscription|query)\b/.exec(source)?.[1] || 'query';
    return { definitions: [{ kind: 'OperationDefinition', operation }] };
  } } } };
  const run = () => vm.runInNewContext(script, {
    document, location, history, URLSearchParams, setTimeout, window,
    requestAnimationFrame(callback) { setImmediate(callback); },
  });
  run();
  return {
    tabs, executions, location,
    error: () => notice?.textContent,
    reload: run,
  };
}

const flush = () => new Promise(resolve => setTimeout(resolve, 20));
const query = 'query Unicode { lookup(text: "Привет + & # %\\n") { name } }';
const variables = JSON.stringify({ text: 'café + & # %', nested: { n: 2 } });
const search = '?' + new URLSearchParams({ query, variables, keep: 'yes' });
const valid = page(search, '#existing');
await flush();
assert.deepEqual(valid.tabs, [
  { query: 'query Existing { __typename }', variables: '{"old":true}' },
  { query, variables },
]);
assert.deepEqual(valid.executions, [{ query, variables }]);
assert.equal(valid.location.search, '?keep=yes');
assert.equal(valid.location.hash, '#existing');
assert.equal(valid.error(), undefined);
valid.reload();
await flush();
assert.equal(valid.tabs.length, 2, 'refresh must not create a duplicate');
assert.equal(valid.executions.length, 1, 'refresh must not execute again');
const redirectedPage = page('', '#' + new URLSearchParams({ query, variables, keep: 'yes' }));
await flush();
assert.deepEqual(redirectedPage.executions, [{ query, variables }]);
assert.equal(redirectedPage.location.search, '');
assert.equal(redirectedPage.location.hash, '#keep=yes');
redirectedPage.reload();
await flush();
assert.equal(redirectedPage.executions.length, 1);
for (const badVariables of ['null', '[]', '3', '"text"', '{broken']) {
  const invalid = page('?' + new URLSearchParams({ query, variables: badVariables }));
  await flush();
  assert.equal(invalid.tabs.length, 1, 'invalid data must not add a tab');
  assert.equal(invalid.executions.length, 0);
  assert.match(invalid.error(), /Variables must be a JSON object/);
  assert.ok(invalid.location.search, 'failed link should remain retryable');
}
const noVariables = page('?query=%7B__typename%7D');
await flush();
assert.deepEqual(noVariables.tabs[1], { query: '{__typename}', variables: '{}' });
assert.deepEqual(noVariables.executions, [{ query: '{__typename}', variables: '{}' }]);
for (const unsafeQuery of ['mutation M { changeThing }', 'subscription S { updates }', 'invalid']) {
  const blocked = page('?' + new URLSearchParams({ query: unsafeQuery }));
  await flush();
  assert.equal(blocked.tabs.length, 1);
  assert.equal(blocked.executions.length, 0);
  assert.match(blocked.error(), /Automatic execution requires|invalid syntax/);
}
const empty = page('?query=+%0A');
await flush();
assert.match(empty.error(), /query must not be empty/);
assert.equal(empty.tabs.length, 1);
const unrelated = page('?some-other-parameter=yes');
await flush();
assert.equal(unrelated.tabs.length, 1);
assert.equal(unrelated.error(), undefined);
console.log('PASS: query auto-execution, URL variables, existing tabs, refresh, mutation rejection, invalid input');
