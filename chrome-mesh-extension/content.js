(() => {
  const querySelector = '.graphiql-query-editor .CodeMirror';
  const variablesSelector = '[aria-label="Variables"] .graphiql-editor:not(.hidden) .CodeMirror';

  function waitFor(read) {
    const deadline = Date.now() + 20000;
    return new Promise((resolve, reject) => {
      function check() {
        const value = read();
        if (value) return resolve(value);
        if (Date.now() >= deadline) {
          return reject(new Error('GraphiQL did not become ready. Reload the original link after the editor has loaded.'));
        }
        setTimeout(check, 50);
      }
      check();
    });
  }

  function showError(error) {
    let notice = document.getElementById('mesh-link-error');
    if (!notice) {
      notice = document.createElement('div');
      notice.id = 'mesh-link-error';
      notice.setAttribute('role', 'alert');
      notice.style.cssText = 'position:fixed;top:8px;right:8px;max-width:420px;padding:12px;z-index:2147483647;background:#fff3cd;color:#332701;border:1px solid #997404;border-radius:4px;font:14px sans-serif;';
      document.body.append(notice);
    }
    notice.textContent = `Mesh query link: ${error.message}`;
  }

  async function openQuery(source, fromHash) {
    const params = new URLSearchParams(fromHash ? source.slice(1) : source);
    const current = () => fromHash ? location.hash : location.search;
    if (!params.has('query') || current() !== source) return;
    const query = params.get('query');
    if (!query.trim()) throw new Error('The query must not be empty.');
    const variables = params.get('variables') ?? '{}';
    let parsed;
    try {
      parsed = JSON.parse(variables);
    } catch {
      throw new Error('Variables must be a JSON object.');
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('Variables must be a JSON object.');
    }

    const parse = await waitFor(() => window.GraphiQL?.GraphQL?.parse);
    let graphqlDocument;
    try {
      graphqlDocument = parse(query);
    } catch {
      throw new Error('The GraphQL query has invalid syntax.');
    }
    const operations = graphqlDocument.definitions.filter(definition => definition.kind === 'OperationDefinition');
    if (operations.length !== 1 || operations[0].operation !== 'query') {
      throw new Error('Automatic execution requires exactly one query operation.');
    }

    await waitFor(() => {
      const button = document.querySelector('button[aria-label="Add tab"]');
      return button && !button.disabled && document.querySelector(querySelector)?.CodeMirror;
    });
    if (current() !== source) return;
    document.getElementById('mesh-link-error')?.remove();

    const tabCount = () => document.querySelectorAll('[role="tablist"] [role="tab"]').length;
    const before = tabCount();
    document.querySelector('button[aria-label="Add tab"]').click();
    await waitFor(() => tabCount() > before);

    // The deployed GraphiQL uses CodeMirror 5. Revisit these selectors/API on a GraphiQL upgrade.
    const queryEditor = await waitFor(() => document.querySelector(querySelector)?.CodeMirror);
    if (!document.querySelector(variablesSelector)) {
      const variablesButton = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === 'Variables');
      variablesButton?.click();
    }
    const variablesEditor = await waitFor(() => document.querySelector(variablesSelector)?.CodeMirror);
    queryEditor.setValue(query);
    variablesEditor.setValue(variables);
    queryEditor.focus();
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));

    // Consume the link so refresh does not create another tab. Keep unrelated URL fields.
    if (current() === source) {
      params.delete('query');
      params.delete('variables');
      const rest = params.toString();
      history.replaceState(history.state, '', fromHash
        ? location.pathname + location.search + (rest ? '#' + rest : '')
        : location.pathname + (rest ? '?' + rest : '') + location.hash);
    }
    const execute = await waitFor(() => {
      const button = document.querySelector('button[aria-label="Execute query (Ctrl-Enter)"]');
      return button && !button.disabled && button;
    });
    execute.click();
  }

  const fromHash = new URLSearchParams(location.hash.slice(1)).has('query');
  openQuery(fromHash ? location.hash : location.search, fromHash).catch(showError);
})();
