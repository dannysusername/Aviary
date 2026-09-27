console.log('dashboard.js loaded');

let timeout;
let userInfoTimeout;
let previousBlockTimeHours = document.getElementById('current-block-time')?.value || 0;
let previousTimeInServiceHours = document.getElementById('current-time-in-service')?.value || 0;
// True from a successful CSV parse until the next Add (or a fresh upload) --
// tells the server this Out/In pair was computed from CSV data, not typed by
// hand, so HoursService.recomputeChain can keep it in sync automatically
// instead of treating it as a fixed manual reading. See UserController.addFlightLog.
let csvPrefilled = false;


// ── Lightweight toast + confirm UI (replaces native alert()/confirm()) ──
function showToast(message, type = 'info') {
    let container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        document.body.appendChild(container);
    }
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add('show'));
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 200);
    }, 3500);
}

function showConfirm(message, confirmLabel = 'Confirm') {
    return new Promise(resolve => {
        const overlay = document.createElement('div');
        overlay.className = 'confirm-overlay';
        overlay.innerHTML =
            '<div class="confirm-box" role="dialog" aria-modal="true">' +
            '  <div class="confirm-message"></div>' +
            '  <div class="confirm-actions">' +
            '    <button type="button" class="confirm-btn cancel">Cancel</button>' +
            '    <button type="button" class="confirm-btn ok"></button>' +
            '  </div>' +
            '</div>';
        overlay.querySelector('.confirm-btn.ok').textContent = confirmLabel;
        overlay.querySelector('.confirm-message').textContent = message;
        document.body.appendChild(overlay);
        requestAnimationFrame(() => overlay.classList.add('show'));

        function onKey(e) { if (e.key === 'Escape') close(false); }
        const close = (result) => {
            overlay.classList.remove('show');
            setTimeout(() => overlay.remove(), 150);
            document.removeEventListener('keydown', onKey);
            resolve(result);
        };
        overlay.querySelector('.confirm-btn.ok').addEventListener('click', () => close(true));
        overlay.querySelector('.confirm-btn.cancel').addEventListener('click', () => close(false));
        overlay.addEventListener('click', (e) => { if (e.target === overlay) close(false); });
        document.addEventListener('keydown', onKey);
    });
}


// ── "Last updated" formatting for the My Hours card ──
function relativeTime(date) {
    const sec = Math.round((Date.now() - date.getTime()) / 1000);
    if (sec < 45) return 'just now';
    const min = Math.round(sec / 60);
    if (min < 60) return min + (min === 1 ? ' minute ago' : ' minutes ago');
    const hr = Math.round(min / 60);
    if (hr < 24) return hr + (hr === 1 ? ' hour ago' : ' hours ago');
    const day = Math.round(hr / 24);
    if (day < 30) return day + (day === 1 ? ' day ago' : ' days ago');
    const mon = Math.round(day / 30);
    if (mon < 12) return mon + (mon === 1 ? ' month ago' : ' months ago');
    const yr = Math.round(mon / 12);
    return yr + (yr === 1 ? ' year ago' : ' years ago');
}

function formatUpdated(iso, source) {
    if (!iso) return 'not set yet';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return 'not set yet';
    const when = d.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
    const label = source === 'flightlog' ? ' · from a flight log'
                : source === 'manual'   ? ' · you edited it'
                : '';
    return 'updated ' + when + ' (' + relativeTime(d) + ')' + label;
}

// Initial render from the server-set data- attributes
function renderUpdatedFromData(elId) {
    const el = document.getElementById(elId);
    if (el) el.textContent = formatUpdated(el.getAttribute('data-updated'), el.getAttribute('data-source'));
}

// When a Block Time / Time in Service reading was actually taken -- distinct
// from formatUpdated above, which is "when this field was last edited."
function formatLogTimestamp(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    return d.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

// Fills every .log-timestamp[data-timestamp] left blank by the server
// (Thymeleaf can't format an Instant into the user's local time zone the
// way the browser can). Call again after inserting rows via JS.
function renderLogTimestamps(root) {
    (root || document).querySelectorAll('.log-timestamp[data-timestamp]').forEach(el => {
        el.textContent = formatLogTimestamp(el.getAttribute('data-timestamp'));
    });
}

// A datetime-local input's value ("2026-04-21T05:07") is naive local wall
// time with no timezone -- `new Date(...)` interprets it as the browser's
// local time, which is exactly what we want to send as a real UTC instant.
function datetimeLocalToIso(value) {
    if (!value) return null;
    const d = new Date(value);
    return isNaN(d.getTime()) ? null : d.toISOString();
}

// Reverse of the above, for prefilling a datetime-local input from a UTC
// instant string (e.g. what the CSV parser returns).
function isoToDatetimeLocal(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

// One flight log row's HTML -- shared by every place that (re)builds the
// table so a row always looks the same regardless of how it got there.
// Dark/light mode. The actual switch to dark happens even earlier, in a
// tiny inline script in dashboard.html's <head> (before CSS paints) so a
// returning user doesn't see a light-mode flash -- this is what Settings'
// theme dropdown calls when the user changes it.
function applyTheme(theme) {
    if (theme === 'dark') {
        document.documentElement.setAttribute('data-theme', 'dark');
    } else {
        document.documentElement.removeAttribute('data-theme');
    }
    try { localStorage.setItem('theme', theme); } catch (e) {}
}

function buildLogRowHtml(log) {
    return `
        <td data-label="From"><span class="print-only">${log.fromAirport || ''}</span><input type="text" name="fromAirport" class="no-print" value="${log.fromAirport || ''}" readonly></td>
        <td data-label="To"><span class="print-only">${log.toAirport || ''}</span><input type="text" name="toAirport" class="no-print" value="${log.toAirport || ''}" readonly></td>
        <td data-label="Block Time Out"><span class="print-only">${log.blockTimeOut ?? ''}</span><input type="number" name="blockTimeOut" class="no-print" value="${log.blockTimeOut ?? ''}" readonly step="0.1"><span class="log-timestamp no-print">${formatLogTimestamp(log.blockTimeStart)}</span></td>
        <td data-label="Block Time In"><span class="print-only">${log.blockTimeIn ?? ''}</span><input type="number" name="blockTimeIn" class="no-print" value="${log.blockTimeIn ?? ''}" readonly step="0.1"><span class="log-timestamp no-print">${formatLogTimestamp(log.blockTimeEnd)}</span></td>
        <td data-label="Time in Service Out"><span class="print-only">${log.timeInServiceOut ?? ''}</span><input type="number" name="timeInServiceOut" class="no-print" value="${log.timeInServiceOut ?? ''}" readonly step="0.1"><span class="log-timestamp no-print">${formatLogTimestamp(log.timeInServiceStart)}</span></td>
        <td data-label="Time in Service In"><span class="print-only">${log.timeInServiceIn ?? ''}</span><input type="number" name="timeInServiceIn" class="no-print" value="${log.timeInServiceIn ?? ''}" readonly step="0.1"><span class="log-timestamp no-print">${formatLogTimestamp(log.timeInServiceEnd)}</span></td>
        <td class="delete-cell no-print" data-label=""><button class="delete-log-icon"><i class="fa-solid fa-trash-can fa-xl"></i></button></td>
    `;
}

// Live render after a change we just made (now + known source)
function markUpdatedNow(elId, source) {
    const el = document.getElementById(elId);
    if (el) el.textContent = formatUpdated(new Date().toISOString(), source);
}

function setTextareaMinHeight(textarea) {
    textarea.style.height = 'auto'; // Reset to measure content
    const scrollHeight = textarea.scrollHeight;
    if (scrollHeight > 140) {
        textarea.style.height = '140px'; // Cap at 120px
        textarea.style.overflowY = 'auto'; // Show scrollbar
    } else {
        textarea.style.height = `${scrollHeight}px`; // Match content height
        textarea.style.overflowY = 'hidden'; // Hide scrollbar
    }
}

function autoSave(input) {
    const row = input.closest('.auto-save-row');
    if (!row) return;

    const id = row.getAttribute('data-id'); //grab 'data-id' of row
    //const status = row.querySelector('.save-status');  grab element in the auto-save-row called 'save-status'
    
    const calValEl  = row.querySelector('input[name="cycleCalendarValue"]');
    const calUnitEl = row.querySelector('select[name="cycleCalendarUnit"]');
    const hrsEl     = row.querySelector('input[name="cycleHours"]');
    const parsedInt = (el) => {
        if (!el || el.value === '' || el.value == null) return null;
        const n = parseInt(el.value, 10);
        return isNaN(n) ? null : n;
    };
    const parsedFloat = (el) => {
        if (!el || el.value === '' || el.value == null) return null;
        const n = parseFloat(el.value);
        return isNaN(n) ? null : n;
    };

    const data = {
        item: row.querySelector('textarea[name="item"]').value, //get rows item name
        description: row.querySelector('input[name="description"]').value, //get rows description option
        cycleCalendarValue: parsedInt(calValEl),
        cycleCalendarUnit:  calUnitEl ? (calUnitEl.value || null) : null,
        cycleHours:         parsedFloat(hrsEl)
    };

    refreshCompleteButtonState(row);

    ['lastDone', 'dueDate'].forEach((field, index) => { //loops through lastDone and dueDate fields
        const container = row.querySelector(`td:nth-child(${index === 0 ? 5 : 6}) .input-with-dropdown`); //grab .input-with-dropdown in td child n
        const dateInput = container.querySelector('input[type="date"]'); //get the 'date' input
        const textInput = container.querySelector('input[type="text"].extra-input'); //get the 'text' input
        data[`${field}Date`]  = dateInput ? (dateInput.value.trim() || null) : null;
        data[`${field}Hours`] = textInput ? (textInput.value.trim() || null) : null;

    });

    const timeLeftSpan = row.querySelector('td:nth-child(7) .time-left');
    if (timeLeftSpan) {
        const currentTimeInServiceHoursInput = document.getElementById('current-time-in-service');
        const currentTimeInServiceHours = currentTimeInServiceHoursInput ? parseFloat(currentTimeInServiceHoursInput.value) || 0 : 0;
        const dueDateCal = data.dueDateDate || '';
        const dueDateHrs = data.dueDateHours || '';
        setTimeLeftText(timeLeftSpan, calculateTimeLeft(dueDateCal, dueDateHrs, currentTimeInServiceHours));
        data.timeLeft = timeLeftSpan.textContent;
    }

    clearTimeout(timeout);
    //status.textContent = '...';
    //status.className = 'save-status saving';

    timeout = setTimeout(() => {
        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');

        console.log(data);
        axios.post(`/update/${id}`, data, {
            headers: { [csrfHeader]: csrfToken }
        })
        .then(response => {
            
            console.log ('Row id: ' + id + ' saved ✓');
        })
        .catch(error => {
            //status.textContent = '✖';
            //status.className = 'save-status error';
            console.error('Error saving:', error.response ? error.response.data : error);
        });
    }, 500);
}

async function loadAeroApiUsage() {
    const display = document.getElementById('aero-usage-display');
    if (!display) return;
    display.textContent = 'checking…';
    try {
        const response = await axios.get('/aeroapi/usage');
        const { totalCost, totalCalls, totalFailedCalls } = response.data;
        display.textContent = `$${totalCost.toFixed(2)} used this month (${totalCalls} calls${totalFailedCalls ? `, ${totalFailedCalls} failed` : ''})`;
    } catch (error) {
        display.textContent = error.response?.data?.error || 'Could not check usage.';
    }
}

function autoSaveUserInfo(input) {
    clearTimeout(userInfoTimeout);

    const data = {};
    data[input.name] = input.value; // Only send the changed field

    userInfoTimeout = setTimeout(() => {
        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');

        axios.post('/updateUserInfo', data, {
            headers: { [csrfHeader]: csrfToken }
        })
        .then(response => {
            console.log('User info saved successfully: ');
            console.log(input.name === 'aeroApiKey' ? '[redacted]' : data);
            // Optionally add a status indicator next to the input if needed

            // NEW: Update the adjacent print-only span with the new value
            const printSpan = input.nextElementSibling;
            if (printSpan && printSpan.classList.contains('print-only')) {
                printSpan.textContent = input.value;
            }

            // The API key is sensitive: after a successful save, re-mask it
            // (last 4 chars) and lock the field, same as on page load.
            if (input.name === 'aeroApiKey' && input.value) {
                const last4 = input.value.slice(-4);
                input.value = '••••' + last4;
                input.readOnly = true;
                const changeLink = document.getElementById('change-api-key');
                if (changeLink) changeLink.hidden = false;
                document.getElementById('aero-usage-row').hidden = false;
                loadAeroApiUsage(); // also serves as "does this key actually work"
            }
        })
        .catch(error => {
            console.error('Error saving user info:', error.response ? error.response.data : error);
            // Optionally show an error indicator
        });
    }, 500); // Debounce for 500ms
}

// In printDashboard, add this loop for redundancy (before window.print())
document.querySelectorAll('.aircraft-info input.user-info-input').forEach(input => {
    const printSpan = input.nextElementSibling;
    if (printSpan && printSpan.classList.contains('print-only')) {
        printSpan.textContent = input.value;
    }
});

function deleteRow(icon) {
    const row = icon.closest('tr');
    if (!row) return;
    const id = row.getAttribute('data-id');
    if (!id) {
        console.error('No data-id found for the row');
        return;
    }
    const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
    showConfirm('Delete this entry?', 'Delete').then(confirmed => {
        if (!confirmed) return;
        axios.delete(`/delete/${id}`, { headers: { [csrfHeader]: csrfToken } })
            .then(() => row.remove())
            .catch(error => {
                console.error('Error deleting:', error.response ? error.response.data : error);
                showToast('Could not delete the entry. Please try again.', 'error');
            });
    });
}

// Builds the human-readable cycle string from a row's structured inputs.
// Used for the print-only column and the Excel export. Empty when no cycle.
function formatCycleDisplay(row) {
    const calVal  = row.querySelector('input[name="cycleCalendarValue"]')?.value;
    const calUnit = row.querySelector('select[name="cycleCalendarUnit"]')?.value;
    const hrs     = row.querySelector('input[name="cycleHours"]')?.value;
    const parts = [];
    if (calVal && parseInt(calVal, 10) > 0 && calUnit) {
        parts.push(`${calVal} ${calUnit.toLowerCase()}`);
    }
    if (hrs && parseFloat(hrs) > 0) {
        parts.push(`${hrs} hrs`);
    }
    return parts.join(' / ');
}

// Disable the Update button on rows where no structured cycle is set yet —
// the server would reject the click anyway, so prevent the round-trip.
function refreshCompleteButtonState(row) {
    const btn = row.querySelector('.complete-btn');
    if (!btn) return;
    const calVal = row.querySelector('input[name="cycleCalendarValue"]')?.value;
    const calUnit = row.querySelector('select[name="cycleCalendarUnit"]')?.value;
    const hrs    = row.querySelector('input[name="cycleHours"]')?.value;
    const hasCal = calVal && parseInt(calVal, 10) > 0 && calUnit;
    const hasHrs = hrs    && parseFloat(hrs)         > 0;
    if (hasCal || hasHrs) {
        btn.removeAttribute('disabled');
        btn.title = 'Mark maintenance complete: sets Last Done to today/current hours and rolls Due Date forward by the cycle.';
    } else {
        btn.setAttribute('disabled', 'disabled');
        btn.title = 'Set a cycle (months/years/days or hours) on this row to enable.';
    }
}

function refreshAllCompleteButtons() {
    document.querySelectorAll('.auto-save-row').forEach(refreshCompleteButtonState);
}

// Click handler for the per-row "complete maintenance" button.
// Server is authoritative for today + current hours; client only repaints
// the row from the response so a stale tab can't desync the displayed value.
async function completeMaintenance(button) {
    const row = button.closest('.auto-save-row');
    if (!row) return;
    const id = row.getAttribute('data-id');
    if (!id) {
        showToast('Could not identify row.', 'error');
        return;
    }
    const confirmed = await showConfirm(
        'Mark this maintenance as just completed? This will reset Last Done to today / current hours and roll Due Date forward by the cycle.',
        'Mark complete');
    if (!confirmed) return;
    try {
        const csrfToken  = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
        const response = await axios.post(`/completeMaintenance/${id}`, {}, {
            headers: { [csrfHeader]: csrfToken, 'Content-Type': 'application/json' }
        });
        const { lastDone, dueDate, timeLeft } = response.data;
        repaintDateHoursCell(row, 'lastDone', lastDone);
        repaintDateHoursCell(row, 'dueDate',  dueDate);
        const parseSplit = (s) => { const [d, ...h] = (s || '').split(' '); return { date: d || '', hours: h.join(' ') }; };
        const ld = parseSplit(lastDone), dd = parseSplit(dueDate);
        row.setAttribute('data-lastDoneDate',  ld.date);
        row.setAttribute('data-lastDoneHours', ld.hours);
        row.setAttribute('data-dueDateDate',   dd.date);
        row.setAttribute('data-dueDateHours',  dd.hours);
        const tl = row.querySelector('td:nth-child(7) .time-left');
        if (tl) setTimeLeftText(tl, timeLeft || 'N/A');
        showToast('Maintenance completed. Due Date rolled forward.', 'success');
    } catch (error) {
        const msg = error?.response?.data?.message || 'Failed to update maintenance.';
        showToast(msg, 'error');
    }
}

// Rebuilds the dual-input cell (date input and/or hours input) from a
// "YYYY-MM-DD <hours>" string. Mirrors the loader at the bottom of dashboard.js
// so manual edits keep working after the button fires.
function repaintDateHoursCell(row, field, value) {
    const tdIndex = field === 'lastDone' ? 5 : 6;
    const container = row.querySelector(`td:nth-child(${tdIndex}) .input-with-dropdown`);
    if (!container) return;
    container.querySelectorAll('input.extra-input').forEach(i => i.remove());
    container.querySelectorAll('.add-type').forEach(btn => btn.textContent = 'Add');
    if (!value) return;

    const parts = value.split(' ');
    let datePart = null, textPart = null;
    if (parts[0] && parts[0].match(/^\d{4}-\d{2}-\d{2}$/)) {
        datePart = parts[0];
        if (parts.length > 1) textPart = parts.slice(1).join(' ');
    } else {
        textPart = value;
    }
    const trigger = container.querySelector('.trigger-dropdown');

    if (datePart) {
        const dateInput = document.createElement('input');
        dateInput.type = 'date';
        dateInput.name = `${field}_date`;
        dateInput.className = 'extra-input';
        dateInput.value = datePart;
        dateInput.oninput = () => autoSave(dateInput);
        container.insertBefore(dateInput, trigger);
        const calBtn = container.querySelector('.add-type[data-type="calendar"]');
        if (calBtn) calBtn.textContent = 'Remove';
    }
    if (textPart) {
        const textInput = document.createElement('input');
        textInput.type = 'text';
        textInput.name = `${field}_text`;
        textInput.className = 'extra-input';
        textInput.placeholder = 'Enter hours';
        textInput.value = textPart;
        textInput.oninput = () => autoSave(textInput);
        container.insertBefore(textInput, trigger);
        const clkBtn = container.querySelector('.add-type[data-type="clock"]');
        if (clkBtn) clkBtn.textContent = 'Remove';
    }
}

function updateOrderOnServer() {
    const rows = document.querySelectorAll('.sortable tr:not(.add-row)');
    const order = Array.from(rows).map(row => row.getAttribute('data-id'));
    const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
    //Gets the row order by getting the 'data-id' of each row and sending that to the /updateOrders endpoint
    //Ex. If order is 3, 1, 2 then that is the order sent
    console.log("Order request sent");
    axios.post('/updateOrder', order, {        
        headers: {
            [csrfHeader]: csrfToken,
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        console.log('Order of rows updated');
    })
    .catch(error => {
        console.error('RESPONSE: Error updating order:', error);
    });
}

document.addEventListener('click', function(event) {
    if (event.target.classList.contains('dropdown-trigger')) {
        event.stopPropagation();
        const dropdown = event.target.parentElement.querySelector('.dropdown-options');
        if (dropdown) {
            dropdown.style.display = dropdown.style.display === 'block' ? 'none' : 'block';
        } else {
            console.error('Dropdown options not found for:', event.target);
        }
    }
});


// Last Done / Due Date menu: put an armed "Remove?" button back to "Remove".
function disarmRemoveButton(button) {
    button.classList.remove('confirm-remove');
    button.textContent = 'Remove';
}

// iOS Safari only applies :active styles (the trash's tap color) when the
// page has a touchstart listener.
document.addEventListener('touchstart', () => {}, { passive: true });

// A dropdown's toggles: its chevron, the Description text, or an empty
// Last Done / Due Date cell (the "Add date / hrs" hint).
function isDropdownToggle(owner, target) {
    if (target === owner && owner.matches('.input-with-dropdown')) return true;
    const toggle = target.closest('.trigger-dropdown, .selected-option');
    return !!toggle && owner.contains(toggle);
}

// Tapping the Description text or an empty date cell opens its menu, same as
// the chevron (a much bigger tap target on phones).
document.addEventListener('click', function(event) {
    const target = event.target;
    if (target.classList.contains('trigger-dropdown')) return;
    const owner = target.closest('.custom-dropdown, .input-with-dropdown');
    if (!owner || !isDropdownToggle(owner, target)) return;
    if (owner.matches('.input-with-dropdown') && owner.querySelector('input.extra-input')) return;
    owner.querySelector('.trigger-dropdown')?.click();
});

// Close any open Description / Last Done / Due Date menu when the user clicks
// anywhere except inside that menu or on its own toggle. Stays registered for
// the life of the page (no one-shot listeners that a click inside the menu
// would use up).
document.addEventListener('click', function(event) {
    const target = event.target;
    if (!target.isConnected) return;   // e.g. a custom option that was just removed
    document.querySelectorAll('.dropdown-options, .type-dropdown').forEach(menu => {
        if (menu.style.display !== 'block' || menu.contains(target)) return;
        const owner = menu.closest('.custom-dropdown, .input-with-dropdown');
        if (owner && isDropdownToggle(owner, target)) return;
        menu.style.display = 'none';
    });
});

function selectOption(option) {
    const dropdown = option.closest('.custom-dropdown');
    const selected = dropdown.querySelector('.selected-option');
    const hiddenInput = dropdown.querySelector('input[type="hidden"]');
    const value = option.getAttribute('data-value');
    const optionText = option.querySelector('span') ? option.querySelector('span').textContent : option.textContent;
    selected.textContent = value === '' ? '' : optionText;
    hiddenInput.value = value;
    dropdown.querySelector('.dropdown-options').style.display = 'none';
    if (dropdown.closest('.auto-save-row')) autoSave(hiddenInput);
}

async function addCustomDescription(button) {
    const container = button.parentElement;
    const input = container.querySelector('.custom-description');
    const customValue = input.value.trim();
    if (!customValue) return;
    if (isDefaultOption(customValue)) { input.value = ''; return; }
    // Already present in any dropdown? Just clear and bail — no double-add.
    if (document.querySelector(`.dropdown-options .option[data-value="${CSS.escape(customValue)}"]`)) {
        input.value = '';
        return;
    }
    try {
        const csrfToken  = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
        const response = await axios.post('/addDescriptionOption',
            { option: customValue },
            { headers: { [csrfHeader]: csrfToken, 'Content-Type': 'application/json' } });
        const { id, option } = response.data;
        updateAllDropdowns(option, id);
        input.value = '';
    } catch (error) {
        const msg = error?.response?.data?.message || 'Failed to add option';
        showToast(msg, 'error');
    }
}

function updateAllDropdowns(newOption, optionId) {
    document.querySelectorAll('.dropdown-options').forEach(dropdown => {
        if (!dropdown.querySelector(`.option[data-value="${CSS.escape(newOption)}"]`)) {
            const optionDiv = document.createElement('div');
            optionDiv.className = 'option custom-option';
            optionDiv.setAttribute('data-value', newOption);
            if (optionId != null) optionDiv.setAttribute('data-option-id', optionId);
            const span = document.createElement('span');
            span.textContent = newOption;
            optionDiv.appendChild(span);
            const removeBtn = document.createElement('button');
            removeBtn.className = 'remove-option-btn';
            removeBtn.textContent = 'x';
            if (optionId != null) {
                removeBtn.setAttribute('data-option-id', optionId);
            } else {
                removeBtn.setAttribute('data-option-value', newOption);
            }
            optionDiv.appendChild(removeBtn);
            optionDiv.onclick = () => selectOption(optionDiv);
            dropdown.insertBefore(optionDiv, dropdown.querySelector('.add-option-container'));
        }
    });
    updateDropdownWidths();
}

function isDefaultOption(value) {
    return ['Inspect', 'Test', 'Replace', 'Overhaul'].includes(value);
}

function updateDropdownWidths() {
    document.querySelectorAll('.dropdown-options').forEach(dropdown => {
        const options = dropdown.querySelectorAll('.option');
        let maxWidth = 0;
        const tempSpan = document.createElement('span');
        tempSpan.style.visibility = 'hidden';
        tempSpan.style.position = 'absolute';
        tempSpan.style.font = getComputedStyle(options[0]).font;
        tempSpan.style.padding = '4px 8px';
        document.body.appendChild(tempSpan);

        options.forEach(option => {
            tempSpan.textContent = option.textContent;
            const width = tempSpan.offsetWidth;
            maxWidth = Math.max(maxWidth, width);
        });

        document.body.removeChild(tempSpan);
        const cappedWidth = Math.min(maxWidth, 150);
        dropdown.style.minWidth = `${cappedWidth}px`;
    });
}

function calculateTimeLeft(dueDateCal, dueDateHrs, currentTimeInServiceHours) {
    //Change to dueDateCal and dueDatehrs

    if (!dueDateCal && !dueDateHrs) return 'N/A';

    const now = new Date();
    let output = '';
    
    const dueDateCalValue = dueDateCal;
    const dueDateTimeValue = dueDateHrs;

    // Calculate days if dueDate has a calendar date
    if (dueDateCalValue) {
        const dueDate = new Date(dueDateCalValue + 'T00:00:00');
        const timeDiff = dueDate - now;
        const daysLeft = Math.ceil(timeDiff / (1000 * 60 * 60 * 24));
        output += daysLeft < 0 ? `${Math.abs(daysLeft)} days overdue` : `${daysLeft} days left`;
    }

    // Calculate hours if dueDate has a clock value
    if (dueDateTimeValue) {
        const dueDateHours = parseFloat(dueDateTimeValue);
        if (!isNaN(dueDateHours) && !isNaN(currentTimeInServiceHours)) {
            const hoursLeft = Math.round((dueDateHours - currentTimeInServiceHours) * 10) / 10;
            const hoursText = hoursLeft < 0 ? `${Math.abs(hoursLeft)} hours overdue` : `${hoursLeft} hours left`;
            output += output ? `\n${hoursText}` : hoursText;
        }
    }

    console.log("calculateTimeLeft OUTPUT--->" + output);

    return output || 'N/A';
}

function setTimeLeftText(cell, text) {
    cell.textContent = text;
    // Color comes from CSS (.time-left / .time-left.overdue) so it follows
    // the light/dark theme; an inline color here would override both.
    cell.classList.toggle('overdue', text.includes('overdue'));
}

// Function to update all Time Left cells in real-time
function updateAllTimeLeft() {
    const currentTimeInServiceHoursInput = document.getElementById('current-time-in-service');
    const currentTimeInServiceHours = currentTimeInServiceHoursInput ? parseFloat(currentTimeInServiceHoursInput.value) || 0 : 0;

    document.querySelectorAll('.auto-save-row').forEach(row => {
        const dueDateContainer = row.querySelector('td:nth-child(6) .input-with-dropdown');
        const timeLeftCell = row.querySelector('td:nth-child(7) .time-left');
        if (timeLeftCell) {
            const dueDateDate = dueDateContainer.querySelector('input[type="date"]');
            const dueDateText = dueDateContainer.querySelector('input[type="text"].extra-input');
            const dueDateCal = dueDateDate ? dueDateDate.value : '';
            const dueDateHrs = dueDateText ? dueDateText.value : '';
            const timeLeftText = calculateTimeLeft(dueDateCal, dueDateHrs, currentTimeInServiceHours);
            setTimeLeftText(timeLeftCell, timeLeftText);
        }
        
    });
}

function updateAddRowTimeLeft() {
    const currentTimeInServiceHoursInput = document.getElementById('current-time-in-service');
    const currentTimeInServiceHours = currentTimeInServiceHoursInput ? parseFloat(currentTimeInServiceHoursInput.value) || 0 : 0;
    const addRow = document.querySelector('.add-row');
    const dueDateContainer = addRow.querySelector('td:nth-child(6) .input-with-dropdown');
    const timeLeftCell = addRow.querySelector('td:nth-child(7) .time-left');

    if (timeLeftCell) {
        const dueDateDate = dueDateContainer.querySelector('input[type="date"]');
        const dueDateText = dueDateContainer.querySelector('input[type="text"].extra-input');
        const dueDateCal = dueDateDate ? dueDateDate.value : '';
        const dueDateHrs = dueDateText ? dueDateText.value : '';

        setTimeLeftText(timeLeftCell, calculateTimeLeft(dueDateCal, dueDateHrs, currentTimeInServiceHours));
    }
}


function scheduleMidnightUpdate() {
    const now = new Date();
    const midnight = new Date(now);
    midnight.setHours(24, 0, 0, 0); // Next midnight
    const timeToMidnight = midnight - now;

    setTimeout(() => {
        updateAllTimeLeft();      // Update all Time Left values
        scheduleMidnightUpdate(); // Schedule for the next day
    }, timeToMidnight);
}

function selectRowType(type, rowTypeElement) {
    const addRow = document.querySelector('.add-row'); //Get the add row
    const itemInputDiv = document.getElementById('itemInput'); //Get the "Enter Item" div
    const titleInputDiv = document.getElementById('titleInput'); //Get the "Enter Title" div
    const itemInput = itemInputDiv.querySelector('textarea'); //Get the item textarea
    const titleInput = titleInputDiv.querySelector('input'); //Get the title input
    const itemHidden = document.getElementById('itemHidden');
    const isTitleHidden = document.getElementById('isTitleHidden');

    // Removes 'selected' class from all options
    document.querySelectorAll('.row-type-option').forEach(opt => opt.classList.remove('selected'));
    // Add 'selected' class to clicked option
    rowTypeElement.classList.add('selected');

    if (type === 'item') {
        itemInputDiv.style.display = 'block';
        titleInputDiv.style.display = 'none';
        addRow.classList.remove('title-mode');
        itemHidden.value = itemInput.value;
        isTitleHidden.value = 'false';
    } else if (type === 'title') {
        itemInputDiv.style.display = 'none';
        titleInputDiv.style.display = 'block';
        addRow.classList.add('title-mode');
        itemHidden.value = titleInput.value;
        isTitleHidden.value = 'true';
    }

    // Update hidden inputs on input change
    itemInput.oninput = () => itemHidden.value = itemInput.value;
    titleInput.oninput = () => itemHidden.value = titleInput.value;
}

document.addEventListener('DOMContentLoaded', () => {
    updateAllTimeLeft(); // Initial call to set Time Left immediately
    updateAddRowTimeLeft();
    scheduleMidnightUpdate();

    // My Hours "last updated" lines (initial render from server data)
    renderUpdatedFromData('block-time-updated');
    renderUpdatedFromData('time-in-service-updated');

    // Flight log rows: when each Block Time/Time in Service reading was taken
    renderLogTimestamps();

    // Theme dropdown: reflect whatever the <head> script already applied,
    // and switch themes live when changed.
    const themeSelect = document.getElementById('theme-select');
    if (themeSelect) {
        themeSelect.value = document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
        themeSelect.addEventListener('change', () => applyTheme(themeSelect.value));
    }

    const usageRow = document.getElementById('aero-usage-row');
    if (usageRow && !usageRow.hidden) loadAeroApiUsage();

    document.querySelectorAll('.user-info-input').forEach(input => {
        input.addEventListener('input', () => autoSaveUserInfo(input));
    });

    // API key is masked (••••xxxx) once saved; clicking "Change" clears the
    // field and unlocks it so a new key can be typed and auto-saved as usual.
    document.getElementById('change-api-key')?.addEventListener('click', (e) => {
        e.preventDefault();
        const input = document.getElementById('aeroApiKey');
        input.value = '';
        input.readOnly = false;
        input.focus();
        e.target.hidden = true;
    });


    document.addEventListener('click', function(event) {
        const removeBtn = event.target.closest('.remove-option-btn');
        if (removeBtn) {
            event.preventDefault();
            event.stopPropagation();
            const optionId = removeBtn.getAttribute('data-option-id');
            const optionValue = removeBtn.getAttribute('data-option-value');
            const optionDiv = removeBtn.closest('.option');
            const deletedValue = optionDiv.getAttribute('data-value');
    
            showConfirm('Delete this option?', 'Delete').then(confirmed => {
                if (!confirmed) return;
                if (optionId) {
                    // Existing option with an ID (from server)
                    const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
                    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
                    axios.delete(`/deleteOption/${optionId}`, {
                        headers: { [csrfHeader]: csrfToken }
                    })
                    .then(response => {
                        if (response.data === "Option deleted") {
                            document.querySelectorAll(`.option.custom-option[data-option-id="${optionId}"]`)
                                .forEach(opt => opt.remove());
                            // Reset dropdowns where this option was selected
                            document.querySelectorAll('.custom-dropdown').forEach(dropdown => {
                                const hiddenInput = dropdown.querySelector('input[type="hidden"]');
                                const selected = dropdown.querySelector('.selected-option');
                                if (hiddenInput && selected && hiddenInput.value === deletedValue) {
                                    selected.textContent = '';
                                    hiddenInput.value = '';
                                    if (dropdown.closest('.auto-save-row')) autoSave(hiddenInput);
                                }
                            });
                            console.log(`Option ${deletedValue} (ID: ${optionId}) deleted successfully`);
                        } else {
                            throw new Error('Deletion failed on server');
                        }
                    })
                    .catch(error => {
                        console.error('Error deleting option:', error);
                        showToast('Failed to delete option: ' + (error.response?.data || error.message), 'error');
                    });
                } else if (optionValue) {
                    // New option without an ID (not yet saved)
                    document.querySelectorAll(`.option.custom-option[data-value="${optionValue}"]`)
                        .forEach(opt => opt.remove());
                    console.log(`New option ${optionValue} removed locally`);
                }
            });
        } else if (event.target.classList.contains('trigger-dropdown')) {
            const sibling = event.target.nextElementSibling;
            if (sibling && sibling.classList.contains('type-dropdown')) {
                // Calendar/clock type dropdown
                const isOpen = sibling.style.display === 'block';
                document.querySelectorAll('.type-dropdown').forEach(d => d.style.display = 'none');
                if (!isOpen) {
                    sibling.querySelectorAll('.add-type.confirm-remove').forEach(disarmRemoveButton);
                    sibling.style.display = 'block';
                }
            } else if (sibling && sibling.classList.contains('dropdown-options')) {
                // Description custom dropdown
                const isOpen = sibling.style.display === 'block';
                document.querySelectorAll('.dropdown-options').forEach(d => d.style.display = 'none');
                if (!isOpen) sibling.style.display = 'block';
            }

        } else if (event.target.classList.contains('add-type')) {
            const button = event.target; //Save button
            const type = button.getAttribute('data-type'); //Calendar or Clock
            const container = button.closest('.input-with-dropdown'); //Grabs the lastDone container
            const tr = button.closest('tr'); //Grabs the closest table row
            const isAddMode = button.textContent === 'Add';
    
            const existingDate = container.querySelector('input[type="date"]');
            const existingText = container.querySelector('input[type="text"].extra-input');
    
            if (isAddMode) {
                // Adding a new input
                if (type === 'calendar' && !existingDate) {
                    const newInput = document.createElement('input');
                    newInput.type = 'date';
                    newInput.className = 'extra-input';
                    newInput.oninput = () => {
                        if (tr.classList.contains('auto-save-row')) {
                            autoSave(newInput);
                        } else {
                            updateAddRowTimeLeft();
                        }
                    };
                    container.insertBefore(newInput, container.querySelector('.trigger-dropdown'));
                    button.textContent = 'Remove';
                } else if (type === 'clock' && !existingText) {
                    const newInput = document.createElement('input');
                    newInput.type = 'text';
                    newInput.className = 'extra-input';
                    newInput.placeholder = 'Enter hours';
                    newInput.oninput = () => {
                        // allow digits and a single decimal point
                        let v = newInput.value.replace(/[^\d.]/g, '');
                        const firstDot = v.indexOf('.');
                        if (firstDot !== -1) v = v.slice(0, firstDot + 1) + v.slice(firstDot + 1).replace(/\./g, '');
                        newInput.value = v;
                        if (tr.classList.contains('auto-save-row')) {
                            autoSave(newInput);
                        } else {
                            updateAddRowTimeLeft();
                        }
                    };
                    container.insertBefore(newInput, container.querySelector('.trigger-dropdown'));
                    button.textContent = 'Remove';
                }
            } else {
                // Removing an existing input. If it holds a value, the first tap
                // only arms the button ("Remove?"); a second tap removes it.
                // Reopening the menu disarms it (see the chevron handler).
                const target = type === 'calendar' ? existingDate : existingText;
                if (target && target.value && !button.classList.contains('confirm-remove')) {
                    button.closest('.type-dropdown').querySelectorAll('.add-type.confirm-remove').forEach(disarmRemoveButton);
                    button.classList.add('confirm-remove');
                    button.textContent = 'Remove?';
                    return;
                }
                button.classList.remove('confirm-remove');
                if (type === 'calendar' && existingDate) {
                    existingDate.remove();
                    button.textContent = 'Add';
                    if (tr.classList.contains('auto-save-row')) {
                        autoSave(button); // Trigger save for sortable rows
                    } else {
                        updateAddRowTimeLeft();
                    }
                } else if (type === 'clock' && existingText) {
                    existingText.remove();
                    button.textContent = 'Add';
                    if (tr.classList.contains('auto-save-row')) {
                        autoSave(button); // Trigger save for sortable rows
                    } else {
                        updateAddRowTimeLeft();
                    }
                }
            }
    
            // Hide the dropdown after action
            button.closest('.type-dropdown').style.display = 'none';
        } else if (event.target.classList.contains('edit-hours-btn')) {
            document.getElementById('add-time-in-service').value = '';
            document.getElementById('add-block-time').value = '';
            const editSection = document.querySelector('.edit-hours-section');
            editSection.style.display = editSection.style.display === 'flex' ? 'none' : 'flex';
        }
    });
    
    function selectOption(option) {
        if (event.target.closest('.remove-option-btn')) {
            return; // Prevent selection on remove button click (icon or text)
        }
        const dropdown = option.closest('.custom-dropdown');
        const selected = dropdown.querySelector('.selected-option');
        const hiddenInput = dropdown.querySelector('input[type="hidden"]');
        const value = option.getAttribute('data-value');
        const optionText = option.querySelector('span') ? option.querySelector('span').textContent : option.textContent;
        selected.textContent = value === '' ? '' : optionText;
        hiddenInput.value = value;
        dropdown.querySelector('.dropdown-options').style.display = 'none';
        if (dropdown.closest('.auto-save-row')) autoSave(hiddenInput);
    }

    /*
    document.querySelectorAll('.auto-save-row textarea[name="item"]').forEach(textarea => {
        setTimeout(() => {
            setTextareaMinHeight(textarea);
        }, 0); // 0ms delay ensures rendering is complete
        textarea.addEventListener('input', () => {
            setTextareaMinHeight(textarea);
        });
    });

    window.addEventListener('resize', () => {
        document.querySelectorAll('.auto-save-row textarea[name="item"], .add-row textarea[name="item"]').forEach(textarea => {
            setTextareaMinHeight(textarea);
        });
    });
    */

    // Initialize Sortable.js
    const tbody = document.querySelector('.sortable');
    Sortable.create(tbody, {
        handle: '.grip-icon', //Restricts dragging to grip-icon
        animation: 150, // 150ms animation for smooth dragging
        // The list is ALWAYS a vertical stack (desktop table rows and mobile
        // cards alike). On mobile each row is `display:grid` with two columns,
        // which makes SortableJS auto-detect the list as HORIZONTAL and use
        // X-axis math for swaps — every card shares the same full-width X span,
        // so upward drags land on the wrong neighbour (or jump to the top).
        // Pinning direction to 'vertical' forces Y-axis swap math. Desktop rows
        // already detect as vertical, so this leaves desktop behaviour identical.
        direction: 'vertical',
        // Native HTML5 drag-and-drop does not fire on touchscreens, so the grip
        // would be dead on mobile. forceFallback makes SortableJS drive the drag
        // with its own pointer/touch handling, which works on touch devices (and
        // is consistent on desktop). touchStartThreshold avoids hijacking taps.
        forceFallback: true,
        // Keep the floating drag copy inside tbody.sortable (not <body>) so the
        // `.sortable tr ...` card styles still apply to it on mobile.
        fallbackOnBody: false,
        fallbackTolerance: 4,
        touchStartThreshold: 4,
        swapThreshold: 0.65,
        onEnd: function (evt) {
            updateOrderOnServer(); //After calls this function
        }
    });

    document.querySelector('.sortable').addEventListener('click', function(e) {
        const titleCell = e.target.closest('.title-row .title-cell');
        if (!titleCell) return;

        // Prevent triggering if clicking the delete icon or grip
        if (e.target.closest('.delete-icon') || e.target.closest('.grip-icon')) return;

        const titleRow = titleCell.closest('tr');
        filterByTitle(titleRow);
    });

    // Mobile/narrow: tap an item card's headline to collapse it down to just the
    // item name; tap again (or tap the collapsed card) to expand. Cards are
    // expanded by default. Delegated on .sortable so dynamically-added rows work
    // too. The 960px gate matches the CSS card breakpoint, so toggling works for
    // the whole card range and stays disabled on the desktop table (>=961px).
    document.querySelector('.sortable').addEventListener('click', function(e) {
        if (!window.matchMedia('(max-width: 960px)').matches) return;
        const itemCell = e.target.closest('td[data-label="Item"]');
        if (!itemCell) return;
        const row = itemCell.closest('tr');
        if (!row || row.classList.contains('title-row') || row.classList.contains('add-row')) return;
        // While expanded, a tap inside the name textarea edits it (don't toggle).
        // While collapsed, a tap anywhere on the card re-expands it.
        if (e.target.closest('textarea') && !row.classList.contains('collapsed')) return;
        row.classList.toggle('collapsed');
    });

    let currentSectionId = null; // To track the current section being viewed
    function filterByTitle(titleRow) {
        currentSectionId = titleRow.getAttribute('data-id');
        // Hide all rows in the sortable tbody
        document.querySelectorAll('.sortable tr').forEach(row => {
            row.style.display = 'none';
        });
        // Show the clicked title row
        titleRow.style.display = '';
        // Show subsequent item rows until the next title row
        let nextRow = titleRow.nextElementSibling;
        while (nextRow && !nextRow.classList.contains('title-row')) {
            nextRow.style.display = '';
            nextRow = nextRow.nextElementSibling;
        }
        // Ensure the add row remains visible
        document.querySelector('.add-row').style.display = '';
        // Add the "Back" button
        addBackButton();
    }

    function addBackButton() {
        const existingButton = document.querySelector('.back-to-full-list-button');
        if (existingButton) existingButton.remove();
    
        const button = document.createElement('button');
        button.textContent = 'Back to Full List';
        button.className = 'back-to-full-list-button';
        button.addEventListener('click', () => {
            document.querySelectorAll('.sortable tr').forEach(row => {
                row.style.display = '';
            });
            button.remove();
            currentSectionId = null; // Reset current section
        });
    
        const table = document.querySelector('table');
        table.parentNode.insertBefore(button, table);
    }

    document.querySelectorAll('.custom-dropdown').forEach(dropdown => {
        const hiddenInput = dropdown.querySelector('input[type="hidden"]');
        const selected = dropdown.querySelector('.selected-option');
        const options = dropdown.querySelectorAll('.option');
        const value = hiddenInput.value || '';
        const matchingOption = Array.from(options).find(opt => opt.getAttribute('data-value') === value);
        selected.textContent = value === '' ? '' : (matchingOption ? (matchingOption.querySelector('span') ? matchingOption.querySelector('span').textContent : matchingOption.textContent) : value);
        options.forEach(option => option.onclick = () => selectOption(option));
    });

    let previousBlockTimeHours = 0;
    let previousTimeInServiceHours = 0;

    // One timer per field. A single shared timer meant that typing in TimeInService
    // within 500ms of typing in BlockTime cancelled the pending BlockTime save.
    let blockTimeout;
    let timeInServiceTimeout;

    // The typed value still waiting out the 500ms debounce, or null if none.
    let pendingBlockTimeSet = null;
    let pendingTimeInServiceSet = null;

    const currentBlockTimeHoursInput = document.getElementById('current-block-time');
    const currentTimeInServiceHoursInput = document.getElementById('current-time-in-service');

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const header = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
        return { [header]: token };
    }

    // Sends "set hours to exactly this". Returns a promise so callers can wait
    // for it to land before doing anything else.
    async function sendSetHours(kind, value) {
        const isBlockTime = kind === 'blockTime';
        const input = isBlockTime ? currentBlockTimeHoursInput : currentTimeInServiceHoursInput;
        const displayId = isBlockTime ? 'current-block-time-display' : 'current-time-in-service-display';
        const label = isBlockTime ? 'Block Time' : 'Time in Service';
        const previous = isBlockTime ? previousBlockTimeHours : previousTimeInServiceHours;

        const params = new URLSearchParams();
        params.append(isBlockTime ? 'newBlockTime' : 'newTimeInService', parseFloat(value));

        try {
            const response = await axios.post('/updateHours', params, { headers: csrfHeaders() });
            if (response.data.status === 'success') {
                if (isBlockTime) previousBlockTimeHours = value; else previousTimeInServiceHours = value;
                document.getElementById(displayId).textContent = `${label}: ${value}`;
                markUpdatedNow(isBlockTime ? 'block-time-updated' : 'time-in-service-updated', 'manual');
                console.log(`${label} hours updated successfully:`,
                    isBlockTime ? response.data.newBlockTime : response.data.newTimeInService);
                return true;
            }
            console.error(`Failed to update ${label} hours:`, response.data.message);
        } catch (error) {
            console.error('Error updating hours:', error.response ? error.response.data : error);
        }
        if (input) input.value = previous;
        document.getElementById(displayId).textContent = `${label}: ${previous}`;
        showToast(`Failed to update ${label} hours.`, 'error');
        return false;
    }

    // Send anything still sitting in a debounce right now, and wait for it.
    // Called before "add hours" so a delayed set can never land afterwards and
    // overwrite the addition.
    async function flushPendingHours() {
        clearTimeout(blockTimeout);
        clearTimeout(timeInServiceTimeout);
        const blockTime = pendingBlockTimeSet;
        const timeInService = pendingTimeInServiceSet;
        pendingBlockTimeSet = null;
        pendingTimeInServiceSet = null;
        if (blockTime !== null) await sendSetHours('blockTime', blockTime);
        if (timeInService !== null) await sendSetHours('timeInService', timeInService);
    }

    if (currentBlockTimeHoursInput) {
        previousBlockTimeHours = currentBlockTimeHoursInput.value || 0;
        currentBlockTimeHoursInput.addEventListener('input', function() {
            updateAllTimeLeft();
            updateAddRowTimeLeft();
            clearTimeout(blockTimeout);
            const newBlockTimeHours = this.value.trim();
            if (newBlockTimeHours === '' || isNaN(parseFloat(newBlockTimeHours))) {
                pendingBlockTimeSet = null;
                return;
            }
            pendingBlockTimeSet = newBlockTimeHours;
            blockTimeout = setTimeout(() => {
                pendingBlockTimeSet = null;
                sendSetHours('blockTime', newBlockTimeHours);
            }, 500);
        });
    }
    if (currentTimeInServiceHoursInput) {
        previousTimeInServiceHours = currentTimeInServiceHoursInput.value || 0;
        currentTimeInServiceHoursInput.addEventListener('input', function() {
            updateAllTimeLeft();
            updateAddRowTimeLeft();
            clearTimeout(timeInServiceTimeout);
            const newTimeInServiceHours = this.value.trim();
            if (newTimeInServiceHours === '' || isNaN(parseFloat(newTimeInServiceHours))) {
                pendingTimeInServiceSet = null;
                return;
            }
            pendingTimeInServiceSet = newTimeInServiceHours;
            timeInServiceTimeout = setTimeout(() => {
                pendingTimeInServiceSet = null;
                sendSetHours('timeInService', newTimeInServiceHours);
            }, 500);
        });
    }

    document.querySelectorAll('.auto-save-row').forEach(row => {
        ['lastDone', 'dueDate'].forEach((field, index) => {
            const container = row.querySelector(`td:nth-child(${index === 0 ? 5 : 6}) .input-with-dropdown`);
            const datePart = row.getAttribute(`data-${field}Date`) || '';
            const textPart = row.getAttribute(`data-${field}Hours`) || '';

            if (datePart) {
                const dateInput = document.createElement('input');
                dateInput.type = 'date';
                dateInput.name = `${field}_date`;
                dateInput.className = 'extra-input';
                dateInput.value = datePart;
                dateInput.oninput = () => autoSave(dateInput);
                container.insertBefore(dateInput, container.querySelector('.trigger-dropdown'));
                container.querySelector('.add-type[data-type="calendar"]').textContent = 'Remove';
            }

            if (textPart) {
                const textInput = document.createElement('input');
                textInput.type = 'text';
                textInput.name = `${field}_text`;
                textInput.className = 'extra-input';
                textInput.placeholder = 'Enter hours';
                textInput.value = textPart;
                textInput.oninput = () => autoSave(textInput);
                container.insertBefore(textInput, container.querySelector('.trigger-dropdown'));
                container.querySelector('.add-type[data-type="clock"]').textContent = 'Remove';
            }
        });
    });

    updateAllTimeLeft();
    refreshAllCompleteButtons();

    const addRowItemTextarea = document.querySelector('.add-row textarea[name="item"]');
    if (addRowItemTextarea) {
        setTextareaMinHeight(addRowItemTextarea);
        addRowItemTextarea.addEventListener('input', () => {
            setTextareaMinHeight(addRowItemTextarea);
        });
    }

    const itemOption = document.querySelector('.row-type-option[data-type="item"]');
    if (itemOption) {
        selectRowType('item', itemOption);
    }

    const addForm = document.querySelector('.add-row form');
    if (addForm) {
        addForm.addEventListener('submit', function(event) {
        event.preventDefault();

        const isTitleHidden = document.getElementById('isTitleHidden');
        const itemInput = document.getElementById('itemInput').querySelector('textarea'); //get item input
        const titleInput = document.getElementById('titleInput').querySelector('input'); //get title input
        const isTitle = isTitleHidden.value === 'true';
        const item = isTitle ? titleInput.value : itemInput.value;

        if (!item) {
            showToast('Please enter an item or title before submitting.', 'error');
            return;
        }

        const description = document.querySelector('.add-row .custom-dropdown input[name="description"]').value;
        const timeLeft = document.querySelector('.add-row .time-left').textContent;
        const addCalVal  = document.querySelector('.add-row input[name="cycleCalendarValue"]')?.value || '';
        const addCalUnit = document.querySelector('.add-row select[name="cycleCalendarUnit"]')?.value || '';
        const addHrs     = document.querySelector('.add-row input[name="cycleHours"]')?.value || '';

        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');

        const data = {
            item: item,
            isTitle: isTitleHidden.value,
            description: description,
            cycleCalendarValue: addCalVal,
            cycleCalendarUnit: addCalUnit,
            cycleHours: addHrs,
            timeLeft: timeLeft,
            ajax: 'true'
        };

        // Send date and hours as separate fields, matching autoSave and the entity.
        ['lastDone', 'dueDate'].forEach(field => {
            const label = field === 'lastDone' ? 'Last Done' : 'Due Date';
            const container = document.querySelector(`.add-row td[data-label="${label}"] .input-with-dropdown`);
            const dateInput = container.querySelector('input[type="date"]');
            const textInput = container.querySelector('input[type="text"].extra-input');
            data[`${field}Date`]  = dateInput ? dateInput.value.trim() : '';
            data[`${field}Hours`] = textInput ? textInput.value.trim() : '';
        });

        console.log("NEW Service timeline row, POST request -->: ", data);

        axios.post('/dashboard', data, {
            headers: {
                [csrfHeader]: csrfToken,
                'Content-Type': 'application/json'
            }
        }) .then(response => {
            const newRowData = response.data;
            const newRow = document.createElement('tr');
            newRow.setAttribute('data-id', newRowData.id); // Use server-provided ID
            if (newRowData.isTitle) {
                newRow.className = 'title-row';
                newRow.innerHTML = `
                    <td class="grip-cell"><span class="grip-icon no-print"><i class="fa-solid fa-grip-vertical"></i></span></td>
                    <td colspan="6" class="title-cell">${newRowData.item}</td>
                    <td class="complete-cell no-print"></td>
                    <td class="delete-cell"><span class="delete-icon no-print" onclick="deleteRow(this)"><i class="fa-solid fa-trash-can fa-xl"></i></span></td>
                `;
            } else {
                newRow.className = 'auto-save-row';
                newRow.setAttribute('data-lastDoneDate',  newRowData.lastDoneDate  ?? '');
                newRow.setAttribute('data-lastDoneHours', newRowData.lastDoneHours ?? '');
                newRow.setAttribute('data-dueDateDate',   newRowData.dueDateDate   ?? '');
                newRow.setAttribute('data-dueDateHours',  newRowData.dueDateHours  ?? '');
                const lastDonePrint = [newRowData.lastDoneDate, newRowData.lastDoneHours].filter(Boolean).join(' ');
                const dueDatePrint  = [newRowData.dueDateDate,  newRowData.dueDateHours].filter(Boolean).join(' ');
                newRow.innerHTML = `
                <td class="grip-cell"><span class="grip-icon no-print"><i class="fa-solid fa-grip-vertical"></i></span></td>
                <td data-label="Item"><textarea name="item" class="no-print" oninput="autoSave(this)">${newRowData.item}</textarea><i class="fa-solid fa-chevron-down card-caret no-print"></i><span class="print-only">${newRowData.item}</span></td>
                <td data-label="Description">
                    <div class="custom-dropdown no-print">
                        <div class="selected-option no-print">${newRowData.description}</div>
                        <input type="hidden" name="description" value="${newRowData.description}">
                        <i class="fa-solid fa-chevron-down trigger-dropdown"></i>
                        <div class="dropdown-options no-print">
                            <div class="option" data-value="">--None--</div>
                            <div class="option" data-value="Inspect">Inspect</div>
                            <div class="option" data-value="Test">Test</div>
                            <div class="option" data-value="Replace">Replace</div>
                            <div class="option" data-value="Overhaul">Overhaul</div>
                            <div class="add-option-container">
                                <input type="text" class="custom-description" placeholder="New option">
                                <button class="add-option-btn" onclick="addCustomDescription(this)">Add</button>
                            </div>
                        </div>
                    </div>
                    <span class="print-only">${newRowData.description}</span>
                </td>
                <td data-label="Cycle">
                    <div class="cycle-structured no-print">
                        <div class="cycle-row">
                            <input type="number" min="1" step="1" name="cycleCalendarValue" class="cycle-num" placeholder="#"
                                value="${newRowData.cycleCalendarValue ?? ''}" oninput="autoSave(this)">
                            <select name="cycleCalendarUnit" class="cycle-unit" onchange="autoSave(this)">
                                <option value="" ${!newRowData.cycleCalendarUnit ? 'selected' : ''}>unit</option>
                                <option value="DAYS"   ${newRowData.cycleCalendarUnit === 'DAYS'   ? 'selected' : ''}>days</option>
                                <option value="MONTHS" ${newRowData.cycleCalendarUnit === 'MONTHS' ? 'selected' : ''}>months</option>
                                <option value="YEARS"  ${newRowData.cycleCalendarUnit === 'YEARS'  ? 'selected' : ''}>years</option>
                            </select>
                        </div>
                        <div class="cycle-row">
                            <input type="number" min="0.1" step="0.1" name="cycleHours" class="cycle-num" placeholder="#"
                                value="${newRowData.cycleHours ?? ''}" oninput="autoSave(this)">
                            <span class="cycle-unit-label">hrs</span>
                        </div>
                    </div>
                </td>
                <td data-label="Last Done">
                    <div class="input-with-dropdown no-print">
                        <i class="fa-solid fa-chevron-down trigger-dropdown"></i>
                        <div class="type-dropdown" style="display: none;">
                            <div class="type-option"><span>Calendar</span><button class="add-type" data-type="calendar">Add</button></div>
                            <div class="type-option"><span>Clock</span><button class="add-type" data-type="clock">Add</button></div>
                        </div>
                    </div>
                    <span class="print-only">${lastDonePrint}</span>
                </td>
                <td data-label="Due Date">
                    <div class="input-with-dropdown no-print">
                        <i class="fa-solid fa-chevron-down trigger-dropdown"></i>
                        <div class="type-dropdown" style="display: none;">
                            <div class="type-option"><span>Calendar</span><button class="add-type" data-type="calendar">Add</button></div>
                            <div class="type-option"><span>Clock</span><button class="add-type" data-type="clock">Add</button></div>
                        </div>
                    </div>
                    <span class="print-only">${dueDatePrint}</span>
                </td>
                <td data-label="Time Left"><div class="time-left">${newRowData.timeLeft ?? ''}</div></td>
                <td class="complete-cell no-print" data-label="">
                    <button type="button" class="complete-btn"
                            title="Mark maintenance complete"
                            onclick="completeMaintenance(this)">
                        <i class="fa-solid fa-rotate-right"></i>
                        <span class="complete-btn-label">Mark complete</span>
                    </button>
                </td>
                <td class="delete-cell" data-label=""><span class="delete-icon no-print" onclick="deleteRow(this)"><i class="fa-solid fa-trash-can fa-xl"></i></span></td>
            `;
                    // Build the lastDone / dueDate inputs from the split fields
                    ['lastDone', 'dueDate'].forEach((field, index) => {
                        const container = newRow.querySelector(`td:nth-child(${index === 0 ? 5 : 6}) .input-with-dropdown`);
                        const datePart = newRowData[`${field}Date`];
                        const textPart = newRowData[`${field}Hours`];

                        if (datePart) {
                            const dateInput = document.createElement('input');
                            dateInput.type = 'date';
                            dateInput.name = `${field}_date`;
                            dateInput.className = 'extra-input';
                            dateInput.value = datePart;
                            dateInput.oninput = () => autoSave(dateInput);
                            container.insertBefore(dateInput, container.querySelector('.trigger-dropdown'));
                            container.querySelector('.add-type[data-type="calendar"]').textContent = 'Remove';
                        }

                        if (textPart) {
                            const textInput = document.createElement('input');
                            textInput.type = 'text';
                            textInput.name = `${field}_text`;
                            textInput.className = 'extra-input';
                            textInput.placeholder = 'Enter hours';
                            textInput.value = textPart;
                            textInput.oninput = () => autoSave(textInput);
                            container.insertBefore(textInput, container.querySelector('.trigger-dropdown'));
                            container.querySelector('.add-type[data-type="clock"]').textContent = 'Remove';
                        }
                    });
                    const dropdown = newRow.querySelector('.custom-dropdown');
                    dropdown.querySelectorAll('.option').forEach(opt => opt.onclick = () => selectOption(opt));
                    updateAllDropdowns(newRowData.description);
                }

                const sortableTbody = document.querySelector('.sortable');

                // Insert the new row in the correct position
                if (currentSectionId && !newRowData.isTitle) {
                    const titleRow = document.querySelector(`tr[data-id="${currentSectionId}"]`);
                    let nextRow = titleRow.nextElementSibling;
                    while (nextRow && !nextRow.classList.contains('title-row')) {
                        nextRow = nextRow.nextElementSibling;
                    }
                    if (nextRow) {
                        sortableTbody.insertBefore(newRow, nextRow);
                    } else {
                        sortableTbody.appendChild(newRow);
                        
                    }
                } else {
                    sortableTbody.appendChild(newRow);
                }

                if (!newRow.classList.contains('title-row')) {
                    const itemTextarea = newRow.querySelector('textarea[name="item"]');
                    if (itemTextarea) {
                        setTextareaMinHeight(itemTextarea);
                        itemTextarea.addEventListener('input', () => {
                            setTextareaMinHeight(itemTextarea);
                        });
                    }
                    // New rows aren't covered by the page-load refreshAllCompleteButtons
                    // pass, so initialize the Update-button state here.
                    refreshCompleteButtonState(newRow);
                }

                // Update the order on the server TAKE A LOOK HERE

                updateOrderOnServer();

                // Reset add row (existing code)
                document.querySelector('.add-row textarea[name="item"]').value = '';
                document.querySelector('.add-row input[name="title"]').value = '';
                const addCalValEl  = document.querySelector('.add-row input[name="cycleCalendarValue"]');
                const addCalUnitEl = document.querySelector('.add-row select[name="cycleCalendarUnit"]');
                const addHrsEl     = document.querySelector('.add-row input[name="cycleHours"]');
                if (addCalValEl)  addCalValEl.value  = '';
                if (addCalUnitEl) addCalUnitEl.value = '';
                if (addHrsEl)     addHrsEl.value     = '';
                document.querySelector('.add-row .custom-dropdown input[name="description"]').value = '';
                document.querySelector('.add-row .selected-option').textContent = '';
                document.querySelectorAll('.add-row .input-with-dropdown input.extra-input').forEach(input => input.remove());
                document.querySelectorAll('.add-row .add-type').forEach(btn => btn.textContent = 'Add');
                document.querySelector('.add-row .time-left').textContent = '';

                selectRowType('item', document.querySelector('.row-type-option[data-type="item"]'));

                const timeLeftCell = newRow.querySelector('.time-left');
                if (timeLeftCell && newRowData.timeLeft) {
                    setTimeLeftText(timeLeftCell, newRowData.timeLeft);
                }
            })
            .catch(error => {
                console.error('Error adding row:', error.response ? error.response.data : error);
                showToast('Error adding row: ' + (error.response?.data || error.message), 'error');
            });
        });
    }
    
    // Function to update all Time Left cells in real-time

    document.addEventListener('click', function(event) {
        
        if (event.target.id === 'add-block-time-btn') {
            const blockTimeToAdd = document.getElementById('add-block-time').value.trim();
            addBlockTimeHours(blockTimeToAdd);

        } else if (event.target.id === 'add-time-in-service-btn') {
            const timeInServiceToAdd = document.getElementById('add-time-in-service').value.trim();
            addTimeInServiceHours(timeInServiceToAdd);
        }
    });

    // Audit list: every suggestion AeroAPI has ever produced, any status.
    // Fetched the first time the <details> is opened, and re-fetched (see
    // allSuggestionsStale below) any time accept/dismiss changes something.
    const allSuggestionsDetails = document.getElementById('all-suggestions-details');
    let allSuggestionsStale = true;
    async function loadAllSuggestionsIfStale() {
        if (!allSuggestionsDetails || !allSuggestionsDetails.open || !allSuggestionsStale) return;
        allSuggestionsStale = false;
        const body = document.getElementById('all-suggestions-body');
        try {
            const response = await axios.get('/flightsuggestions/all');
            body.innerHTML = response.data.map(s => `
                <tr data-id="${s.id}">
                    <td>${s.origin || ''}</td>
                    <td>${s.destination || ''}</td>
                    <td>${new Date(s.departureTime).toLocaleString()}</td>
                    <td>${new Date(s.arrivalTime).toLocaleString()}</td>
                    <td>${(s.minutesAirborne / 60).toFixed(1)} hrs</td>
                    <td>${s.status}</td>
                    <td>
                        <button class="audit-add-btn">Add to log</button>
                        <button class="audit-delete-btn">Delete</button>
                    </td>
                </tr>
            `).join('') || '<tr><td colspan="7">No suggestions yet.</td></tr>';
        } catch (error) {
            body.innerHTML = '<tr><td colspan="6">Could not load suggestions.</td></tr>';
        }
    }
    if (allSuggestionsDetails) {
        allSuggestionsDetails.addEventListener('toggle', loadAllSuggestionsIfStale);
    }

    // Re-fetches every flight log (already sorted chronologically by the
    // server) and rebuilds the table from scratch. Needed instead of
    // patching in just the one row that changed: adding or deleting a log
    // can shift the Out/In numbers on OTHER rows too now that
    // HoursService.recomputeChain keeps CSV/AeroAPI rows chronologically
    // accurate, so those rows have to be re-rendered as well. Still no full
    // page reload -- just this table.
    async function refreshLogbookRows() {
        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
        const response = await axios.get('/flightlogs', { headers: { [csrfHeader]: csrfToken } });

        document.querySelectorAll('#logbook-body .log-row').forEach(row => row.remove());
        const tbody = document.getElementById('logbook-body');
        const addLogRow = document.querySelector('.add-log-row');

        response.data.forEach(log => {
            const newRow = document.createElement('tr');
            newRow.className = 'log-row';
            newRow.dataset.id = log.id;
            newRow.innerHTML = buildLogRowHtml(log);
            if (addLogRow) tbody.insertBefore(newRow, addLogRow); else tbody.appendChild(newRow);
            wireDeleteLogButton(newRow.querySelector('.delete-log-icon'));
        });

        updateAllTimeLeft();
        updateAddRowTimeLeft();
    }

    // Shared by both places a suggestion can be turned into a log entry:
    // the pending-banner Add button, and the audit list's Add button.
    async function insertAcceptedFlightLogRow(log) {
        await refreshLogbookRows();

        if (log.newTimeInService !== undefined && log.newTimeInService !== null) {
            document.getElementById('current-time-in-service').value = log.newTimeInService;
            document.getElementById('current-time-in-service-display').textContent = `Time in Service: ${log.newTimeInService}`;
            previousTimeInServiceHours = log.newTimeInService;
            markUpdatedNow('time-in-service-updated', 'flightlog');
        }
    }

    document.addEventListener('input', (e) => {
        if (e.target.matches('.cycle-num, .cycle-num-hours') && e.target.value.length > 6) {
            e.target.value = e.target.value.slice(0, 6);
        }
    }, true);

    // Audit list row actions: Add back to log book, or permanently delete.
    document.addEventListener('click', async function(event) {
        const isAdd = event.target.classList.contains('audit-add-btn');
        const isDelete = event.target.classList.contains('audit-delete-btn');
        if (!isAdd && !isDelete) return;

        const row = event.target.closest('tr');
        const id = row.getAttribute('data-id');
        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');

        if (isDelete) {
            const confirmed = await showConfirm(
                'Permanently delete this suggested flight? This cannot be undone.', 'Delete');
            if (!confirmed) return;
            try {
                await axios.delete(`/flightsuggestions/${id}`, { headers: { [csrfHeader]: csrfToken } });
                row.remove();
                showToast('Suggested flight deleted.', 'success');
            } catch (error) {
                showToast(error.response?.data?.message || 'Could not delete that flight.', 'error');
            }
            return;
        }

        try {
            const response = await axios.post(`/flightsuggestions/${id}/accept`, {},
                { headers: { [csrfHeader]: csrfToken } });
            await insertAcceptedFlightLogRow(response.data);
            row.querySelector('td:nth-last-child(2)').textContent = 'accepted'; // status column
            showToast('Added to your flight log.', 'success');
        } catch (error) {
            showToast(error.response?.data?.message || 'Could not add that flight.', 'error');
        }
    });

    // AeroAPI-detected flights: Add/Dismiss. See docs/ADSB_SYNC_SPEC.md "UX flow".
    document.addEventListener('click', function(event) {
        const isAccept = event.target.classList.contains('suggestion-accept-btn');
        const isDismiss = event.target.classList.contains('suggestion-dismiss-btn');
        if (!isAccept && !isDismiss) return;

        const row = event.target.closest('.flight-suggestion-row');
        const id = row.getAttribute('data-id');
        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
        const url = `/flightsuggestions/${id}/${isAccept ? 'accept' : 'dismiss'}`;

        axios.post(url, {}, { headers: { [csrfHeader]: csrfToken } })
            .then(response => {
                row.remove();
                allSuggestionsStale = true;
                loadAllSuggestionsIfStale(); // no-op unless the audit list is open

                // Update the "N flight(s) detected" count, hide the whole
                // banner once nothing's left -- stay on this tab, no reload.
                const banner = document.querySelector('.flight-suggestions');
                if (banner) {
                    const remaining = banner.querySelectorAll('.flight-suggestion-row').length;
                    if (remaining === 0) {
                        banner.hidden = true;
                    } else {
                        banner.querySelector('h3 span').textContent = remaining;
                    }
                }

                if (isAccept) {
                    insertAcceptedFlightLogRow(response.data);
                }
            })
            .catch(error => {
                console.error('Flight suggestion action failed:', error.response ? error.response.data : error);
                showToast(error.response?.data?.message || 'Could not update that flight.', 'error');
            });
    });
    
    async function addBlockTimeHours(blockTimeToAdd) {
        if (blockTimeToAdd && !isNaN(blockTimeToAdd)) {
            await flushPendingHours();
            await updateHours({ blockTimeToAdd: parseFloat(blockTimeToAdd) });
        }
    }
    
    // Updated addTimeInServiceHours (now uses updateHours)
    async function addTimeInServiceHours(timeInServiceToAdd) {
        if (timeInServiceToAdd && !isNaN(timeInServiceToAdd)) {
            await flushPendingHours();
            await updateHours({ timeInServiceToAdd: parseFloat(timeInServiceToAdd) });
        }
    }

    async function updateHours(updateParams) {
        if (Object.keys(updateParams).length === 0) return;
    
        const params = new URLSearchParams();
        Object.entries(updateParams).forEach(([key, value]) => {
            params.append(key, value);
        });
    
        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
    
        try {
            const response = await axios.post('/updateHours', params, {
                headers: { [csrfHeader]: csrfToken }
            });
    
            if (response.data.status === 'success') {
                const { newBlockTime, newTimeInService } = response.data;
    
                if (newBlockTime !== undefined) {
                    document.getElementById('current-block-time').value = newBlockTime;
                    document.getElementById('current-block-time-display').textContent = `Block Time: ${newBlockTime}`;
                    previousBlockTimeHours = newBlockTime;
                    console.log('Block time updated successfully:', newBlockTime);
                }
    
                if (newTimeInService !== undefined) {
                    document.getElementById('current-time-in-service').value = newTimeInService;
                    document.getElementById('current-time-in-service-display').textContent = `Time in Service: ${newTimeInService}`;
                    previousTimeInServiceHours = newTimeInService;
                    console.log('Time in Service updated successfully:', newTimeInService);
                }
    
                if ('blockTimeToAdd' in updateParams) markUpdatedNow('block-time-updated', 'manual');
                if ('timeInServiceToAdd' in updateParams) markUpdatedNow('time-in-service-updated', 'manual');

                // Update time left displays if needed (from your existing functions)
                updateAllTimeLeft();
                updateAddRowTimeLeft();
            } else {
                console.error('Failed to update hours:', response.data.message);
                showToast('Failed to update hours.', 'error');
            }
        } catch (error) {
            console.error('Error updating hours:', error.response ? error.response.data : error);
            showToast('Error updating hours.', 'error');
        }
    }

    // New: Export button listener
    const exportBtn = document.getElementById('export-excel');
    if (exportBtn) {
        exportBtn.addEventListener('click', exportToExcel);
    }

    // Helper to generate Excel formula for Time Left (mimics calculateTimeLeft)
    function getTimeLeftFormula(row) { // row is 1-based Excel row
        return `=IF(E${row}="","N/A",IF(ISERROR(DATEVALUE(LEFT(E${row},10))),LET(hourVal,VALUE(E${row}),IF(hourVal>B$1,(hourVal-B$1)&" hours left",ABS(hourVal-B$1)&" hours overdue")),LET(dateVal,DATEVALUE(LEFT(E${row},10)),spacePos,FIND(" ",E${row}&" "),hourStr,IF(spacePos=11,"",MID(E${row},spacePos+1,99)),hourVal,IF(hourStr="",NA(),VALUE(hourStr)),daysTxt,IF(dateVal>TODAY(),(dateVal-TODAY())&" days left",IF(dateVal<TODAY(),ABS(dateVal-TODAY())&" days overdue","Due today")),hoursTxt,IF(ISNA(hourVal),"",IF(hourVal>B$1,(hourVal-B$1)&" hours left",ABS(hourVal-B$1)&" hours overdue")),IF(hoursTxt="",daysTxt,daysTxt&CHAR(10)&hoursTxt))))`;
    }

    // Main export function
    function exportToExcel() {
        const currentTimeInServiceHoursInput = document.getElementById('current-time-in-service');
        const currentTimeInServiceHours = currentTimeInServiceHoursInput ? parseFloat(currentTimeInServiceHoursInput.value) || 0 : 0;

        // Build array of arrays (aoa) for the sheet
        let aoa = [
            ["Current Hours", currentTimeInServiceHours], // Row 1: Reference for hour calcs (user can update B1 in Excel)
            ["Item", "Description", "Cycle", "Last Done", "Due Date", "Time Left"] // Row 2: Headers
        ];

        let dataRowIndices = []; // Track 0-based indices in aoa for data rows (to set formulas later)

        // Loop over table rows (skip add-row)
        document.querySelectorAll('.sortable tr:not(.add-row)').forEach(row => {
            if (row.classList.contains('title-row')) {
                // Title row: Flatten to Item column
                const title = row.querySelector('.title-cell').textContent.trim();
                aoa.push([title, "", "", "", "", ""]);
            } else {
                // Data row: Extract values (similar to autoSave)
                const item = row.querySelector('textarea[name="item"]')?.value || '';
                const desc = row.querySelector('input[name="description"]')?.value || '';
                const cycle = formatCycleDisplay(row);
                let lastDone = '';
                let dueDate = '';

                ['lastDone', 'dueDate'].forEach((field, index) => {
                    const tdIndex = index + 5; // td 5=lastDone, 6=dueDate (1-based, grip=1)
                    const container = row.querySelector(`td:nth-child(${tdIndex}) .input-with-dropdown`);
                    const dateInput = container?.querySelector('input[type="date"]');
                    const textInput = container?.querySelector('input[type="text"].extra-input');
                    let value = '';
                    if (dateInput) value += dateInput.value;
                    if (textInput) value += value ? ` ${textInput.value}` : textInput.value;
                    if (field === 'lastDone') lastDone = value.trim();
                    else dueDate = value.trim();
                });

                // Compute initial Time Left for display (using your function)
                const [dueDateCalPart, dueDateHrsPart] = dueDate.split(' ');
                const initialTimeLeft = calculateTimeLeft(dueDateCalPart || '', dueDateHrsPart || '', currentTimeInServiceHours);

                aoa.push([item, desc, cycle, lastDone, dueDate, initialTimeLeft]);
                dataRowIndices.push(aoa.length - 1); // Track for formula
            }
        });

        // Create worksheet from aoa
        const wb = XLSX.utils.book_new();
        const ws = XLSX.utils.aoa_to_sheet(aoa);

        // Set formulas on Time Left column (F, col 5) for data rows
        dataRowIndices.forEach(rowIdx => {
            const excelRow = rowIdx + 1; // 1-based for formula
            const cellRef = XLSX.utils.encode_cell({ r: rowIdx, c: 5 }); // F column
            ws[cellRef].f = getTimeLeftFormula(excelRow);
        });

        // Optional: Set column widths for better readability
        const colWidths = [
            { wch: 20 }, // Item
            { wch: 15 }, // Description
            { wch: 10 }, // Cycle
            { wch: 15 }, // Last Done
            { wch: 20 }, // Due Date
            { wch: 25 }  // Time Left (multi-line)
        ];
        ws['!cols'] = colWidths;

        // Add sheet and download
        XLSX.utils.book_append_sheet(wb, ws, "Service Timeline");
        XLSX.writeFile(wb, `Aircraft_Service_Timeline_${new Date().toISOString().split('T')[0]}.xlsx`);
    }

    // Share menu: toggle open/closed, close on an outside click, and wire the
    // two buttons that need nothing external. Print and Download PDF are the
    // same browser action (window.print()) -- there's no separate JS API to
    // save a PDF straight to disk, only the native print dialog, where
    // "Save as PDF" is one of the destinations the user picks themselves.
    // Email/Text stay disabled (.share-coming-soon) until SendGrid/Twilio are
    // actually set up -- see docs/SHARE_EXPORT_SPEC.md.
    const shareToggleBtn = document.getElementById('share-toggle-btn');
    const shareDropdown = document.getElementById('share-dropdown');
    if (shareToggleBtn && shareDropdown) {
        shareToggleBtn.addEventListener('click', (event) => {
            event.stopPropagation();
            shareDropdown.style.display = shareDropdown.style.display === 'flex' ? 'none' : 'flex';
        });
        document.addEventListener('click', (event) => {
            if (shareDropdown.style.display === 'flex' && !event.target.closest('.share-menu')) {
                shareDropdown.style.display = 'none';
            }
        });
    }

    const sharePrintBtn = document.getElementById('share-print-btn');
    if (sharePrintBtn) {
        sharePrintBtn.addEventListener('click', () => {
            shareDropdown.style.display = 'none';
            printDashboard();
        });
    }

    const shareDownloadPdfBtn = document.getElementById('share-download-pdf-btn');
    if (shareDownloadPdfBtn) {
        shareDownloadPdfBtn.addEventListener('click', () => {
            shareDropdown.style.display = 'none';
            downloadDashboardPdf();
        });
    }

    document.querySelectorAll('.share-coming-soon').forEach(button => {
        button.addEventListener('click', () => showToast('Coming soon.', 'info'));
    });

    // NEW: Print button listener
    const printBtn = document.getElementById('print-dashboard');
    if (printBtn) {
        printBtn.addEventListener('click', printDashboard);
    }

    // NEW: Function to handle printing
    // Shared by Print and Download PDF: refreshes every .print-only span
    // (which mirrors a live input's current value) so whichever one runs
    // captures up-to-date numbers, not whatever was on the page at load.
    function refreshPrintOnlyValues() {
        updateAllTimeLeft();
        updateAddRowTimeLeft();  // If applicable, though add-row is hidden

        // Update print-only spans in table rows with current values
        document.querySelectorAll('.sortable tr:not(.title-row)').forEach(row => {
            // Item
            const itemTextarea = row.querySelector('textarea[name="item"]');
            const itemPrint = row.querySelector('td:nth-child(2) .print-only');
            if (itemTextarea && itemPrint) itemPrint.textContent = itemTextarea.value;

            // Description (from hidden input)
            const descInput = row.querySelector('input[name="description"]');
            const descPrint = row.querySelector('td:nth-child(3) .print-only');
            if (descInput && descPrint) descPrint.textContent = descInput.value;

            // Cycle (from structured fields)
            const cyclePrint = row.querySelector('td:nth-child(4) .print-only');
            if (cyclePrint) cyclePrint.textContent = formatCycleDisplay(row);

            // Last Done (construct from inputs)
            const lastDoneContainer = row.querySelector('td:nth-child(5) .input-with-dropdown');
            const lastDoneDate = lastDoneContainer?.querySelector('input[type="date"]')?.value || '';
            const lastDoneText = lastDoneContainer?.querySelector('input[type="text"].extra-input')?.value || '';
            const lastDonePrint = row.querySelector('td:nth-child(5) .print-only');
            if (lastDonePrint) lastDonePrint.textContent = `${lastDoneDate} ${lastDoneText}`.trim();

            // Due Date (similar)
            const dueDateContainer = row.querySelector('td:nth-child(6) .input-with-dropdown');
            const dueDateDate = dueDateContainer?.querySelector('input[type="date"]')?.value || '';
            const dueDateText = dueDateContainer?.querySelector('input[type="text"].extra-input')?.value || '';
            const dueDatePrint = row.querySelector('td:nth-child(6) .print-only');
            if (dueDatePrint) dueDatePrint.textContent = `${dueDateDate} ${dueDateText}`.trim();
        });
    }

    function printDashboard() {
        refreshPrintOnlyValues();
        window.print();
    }

    // Download PDF: the server renders real PDF bytes (PdfExportService,
    // openhtmltopdf) and returns them with Content-Disposition: attachment,
    // so a plain navigation is enough -- the browser handles the download
    // natively, no CSRF token needed since GET isn't a protected method.
    // Replaced an earlier client-side html2canvas+jsPDF approach that
    // screenshotted the dashboard into a slow-to-scroll rasterized PDF.
    function downloadDashboardPdf() {
        window.location.href = '/pdf';
    }

    const subBtn = document.getElementById('subscribe');
    if(subBtn) {
        subBtn.addEventListener('click', subscriptionToggle);
    }

    const deleteSubBtn = document.getElementById('delete-subscription');
    if (deleteSubBtn) {
        deleteSubBtn.addEventListener('click', async () => {
            if (deleteSubBtn.classList.contains('disabled')) {
                showToast('Unsubscribe before deleting the subscription.', 'error');
                return;
            }
            const ok = await showConfirm('Delete this subscription and its saved schedule?', 'Delete');
            if (!ok) return;
            const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
            try {
                await axios.delete('/subscription', { headers: { [csrfHeader]: csrfToken } });
                deleteSubBtn.hidden = true;
                const regInput = document.getElementById('sync-registration');
                if (regInput) regInput.value = '';
                showToast('Subscription deleted.', 'info');
            } catch (error) {
                showToast(error.response?.data?.error || 'Could not delete subscription.', 'error');
            }
        });
    }

    // Once subscribed, flag it when the tail number shown on the dashboard
    // isn't the aircraft flight sync is actually watching (case-insensitive),
    // and offer to line them up. Remembers a "leave it" answer for that exact
    // pair so it doesn't ask again on every reload.
    async function maybeOfferTailNumberSync(registration) {
        const display = document.querySelector('input[name="tailNumber"]');
        if (!display || !registration) return;
        const shown = display.value.trim();
        if (!shown || shown.toUpperCase() === registration.toUpperCase()) return;

        const dismissKey = 'tailMismatchDismissed:' + shown.toUpperCase() + '>' + registration.toUpperCase();
        try { if (localStorage.getItem(dismissKey)) return; } catch (e) {}

        const ok = await showConfirm(
            `The tail number on your dashboard ("${shown}") isn't the aircraft flight sync is subscribed to ("${registration}"). ` +
            `Change the dashboard tail number to "${registration}"?`,
            'Change it');
        if (!ok) {
            try { localStorage.setItem(dismissKey, '1'); } catch (e) {}
            return;
        }
        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
        try {
            await axios.post('/updateUserInfo', { tailNumber: registration },
                { headers: { [csrfHeader]: csrfToken } });
            display.value = registration;
            const printSpan = display.nextElementSibling;
            if (printSpan && printSpan.classList.contains('print-only')) printSpan.textContent = registration;
        } catch (e) {
            showToast('Could not update the dashboard tail number.', 'error');
        }
    }

    function applySubscriptionState(active) {
        subBtn.textContent = active ? 'Unsubscribe' : 'Subscribe';
        const regInput = document.getElementById('sync-registration');
        if (regInput) regInput.readOnly = active;
        if (deleteSubBtn) {
            deleteSubBtn.hidden = false;
            deleteSubBtn.classList.toggle('disabled', active);
        }
        for (const id of ['check-now-btn', 'check-now-info', 'check-now-range-row', 'sync-schedule-row']) {
            const el = document.getElementById(id);
            if (el) el.hidden = !active;
        }
    }

    if (subBtn && subBtn.textContent.trim() === 'Unsubscribe') {
        const reg = document.getElementById('sync-registration')?.value.trim();
        if (reg) maybeOfferTailNumberSync(reg);
    }

    // Date inputs default to no browser-side min/max restriction, so set
    // them from the server-provided bounds (AeroApiClient.MAX_START_DAYS_BACK
    // / MAX_END_DAYS_AHEAD via the check-now-start data attributes) -- keeps
    // the picker from ever offering a date AeroAPI would reject anyway.
    const checkNowStartInput = document.getElementById('check-now-start');
    const checkNowEndInput = document.getElementById('check-now-end');
    if (checkNowStartInput) {
        const maxStartDaysBack = parseInt(checkNowStartInput.dataset.maxStartDaysBack, 10);
        const maxEndDaysAhead = parseInt(checkNowStartInput.dataset.maxEndDaysAhead, 10);
        const toIsoDate = (d) => d.toISOString().slice(0, 10);
        const today = new Date();

        const minStart = new Date(today);
        minStart.setDate(minStart.getDate() - maxStartDaysBack);
        const maxEnd = new Date(today);
        maxEnd.setDate(maxEnd.getDate() + maxEndDaysAhead);

        checkNowStartInput.min = toIsoDate(minStart);
        checkNowStartInput.max = toIsoDate(maxEnd);
        if (checkNowEndInput) {
            checkNowEndInput.min = toIsoDate(minStart);
            checkNowEndInput.max = toIsoDate(maxEnd);
        }
    }

    const clearCheckNowRangeBtn = document.getElementById('clear-check-now-range');
    if (clearCheckNowRangeBtn) {
        clearCheckNowRangeBtn.addEventListener('click', () => {
            if (checkNowStartInput) checkNowStartInput.value = '';
            if (checkNowEndInput) checkNowEndInput.value = '';
        });
    }

    const checkNowBtn = document.getElementById('check-now-btn');
    if (checkNowBtn) {
        checkNowBtn.addEventListener('click', async () => {
            const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
            const originalText = checkNowBtn.textContent;
            checkNowBtn.textContent = 'Checking…';
            checkNowBtn.disabled = true;

            const params = new URLSearchParams();
            if (checkNowStartInput?.value) params.append('start', checkNowStartInput.value);
            if (checkNowEndInput?.value) params.append('end', checkNowEndInput.value);

            try {
                const response = await axios.post(`/subscription/check-now?${params}`, {},
                    { headers: { [csrfHeader]: csrfToken } });
                const newFlights = response.data.newFlights;
                showToast(newFlights > 0 ? `${newFlights} new flight(s) found.` : 'No new flights.', 'success');
                loadAeroApiUsage(); // this call just spent against the account's usage
                if (newFlights > 0) location.reload(); // show them in the banner
            } catch (error) {
                showToast(error.response?.data?.error || 'Check failed.', 'error');
            } finally {
                checkNowBtn.textContent = originalText;
                checkNowBtn.disabled = false;
            }
        });
    }

    const saveSyncBtn = document.getElementById('save-sync-settings');
    if (saveSyncBtn) {
        saveSyncBtn.addEventListener('click', async () => {
            const pollIntervalDays = parseInt(document.getElementById('poll-interval-days').value, 10);
            const preferredCheckHour = parseInt(document.getElementById('preferred-check-hour').value, 10);
            const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');

            try {
                await axios.post('/subscription/settings',
                    { pollIntervalDays, preferredCheckHour },
                    { headers: { [csrfHeader]: csrfToken, 'Content-Type': 'application/json' } }
                );
                showToast('Sync schedule saved.', 'success');
            } catch (error) {
                showToast(error.response?.data?.error || 'Could not save sync schedule.', 'error');
            }
        });
    }

    async function subscriptionToggle() {
        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
        const isSubscribed = subBtn.textContent.trim() === 'Unsubscribe';
        const regInput = document.getElementById('sync-registration');

        try {
            let active;
            if (isSubscribed) {
                const response = await axios.post('/subscription/unsubscribe', {},
                    { headers: { [csrfHeader]: csrfToken } });
                active = response.data.active;
            } else {
                const registration = regInput ? regInput.value.trim() : '';
                if (!registration) {
                    showToast('Enter a registration number to subscribe.', 'error');
                    return;
                }
                const response = await axios.post('/subscription/subscribe',
                    { registration },
                    { headers: { [csrfHeader]: csrfToken, 'Content-Type': 'application/json' } }
                );
                active = response.data.active;
                if (regInput) regInput.value = response.data.registration;
            }

            applySubscriptionState(active);
            showToast(active ? 'Flight sync turned on.' : 'Flight sync turned off.', active ? 'success' : 'info');
            if (active && regInput) {
                // Server seeds User.tailNumber from the registration when it was
                // blank; mirror that in the already-rendered field so it doesn't
                // look empty until the next reload.
                const display = document.querySelector('input[name="tailNumber"]');
                if (display && !display.value.trim()) {
                    display.value = regInput.value.trim();
                    const printSpan = display.nextElementSibling;
                    if (printSpan && printSpan.classList.contains('print-only')) printSpan.textContent = display.value;
                }
                maybeOfferTailNumberSync(regInput.value.trim());
            }

        } catch (error){
            if(!error.response) {
                showToast('Could not reach the server. Check your connection.', 'error');
            } else if (error.response.status === 401) {
                showToast('Your session expired. Please log in again.', 'error');
            } else if (error.response.status === 400) {
                showToast(error.response.data?.error || 'That registration was rejected.', 'error');
            } else {
                showToast('Something went wrong. Try again.', 'error');
            }
            console.error('Subscription toggle failed:', error.response?.data || error.message);
        }

    }

    const activeButton = document.querySelector('.tab-button.active');
    if (activeButton) {
        const initialTabId = activeButton.dataset.tab;
        document.querySelectorAll('.tab-content').forEach(content => {
            content.style.display = 'none';
        });
        document.getElementById(initialTabId).style.display = 'block';
    }

    // NEW: Tab switching
    document.querySelectorAll('.tab-button').forEach(button => {
        button.addEventListener('click', () => {
            document.querySelectorAll('.tab-button').forEach(btn => btn.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(content => content.style.display = 'none');
            button.classList.add('active');
            document.getElementById(button.dataset.tab).style.display = 'block';
        });
    });

    // Settings <-> Dashboard: one-way-door navigation, CSS-driven via the .active class
    // (.front-page is display:none by default, .front-page.active is display:block --
    // see dashboardstyle.css). No logic needed: #dashboard-page already has
    // .active hardcoded in the HTML, so it's visible as soon as the CSS loads.
    document.getElementById('settings').addEventListener('click', () => {
        document.getElementById('dashboard-page').classList.remove('active');
        document.getElementById('settings-page').classList.add('active');
    });

    document.getElementById('back-to-dashboard').addEventListener('click', () => {
        document.getElementById('settings-page').classList.remove('active');
        document.getElementById('dashboard-page').classList.add('active');
    });

    // NEW: Add log row via AJAX
    // Wire up the delete listener for one flight-log row's delete button.
    function wireDeleteLogButton(button) {
        button.addEventListener('click', async () => {
            const confirmed = await showConfirm('Delete this flight log entry?', 'Delete');
            if (!confirmed) return;
            const row = button.closest('tr');
            const id = row.dataset.id;
            const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
            try {
                const deleteResponse = await axios.delete(`/deleteflightlog/${id}`, {
                    headers: { [csrfHeader]: csrfToken }
                });
                allSuggestionsStale = true; // deleting can reset a suggestion back to pending
                const { newBlockTime, newTimeInService } = deleteResponse.data;
                if (newBlockTime !== undefined) {
                    document.getElementById('current-block-time').value = newBlockTime;
                    document.getElementById('current-block-time-display').textContent = `Block Time: ${newBlockTime}`;
                    previousBlockTimeHours = newBlockTime;
                }
                if (newTimeInService !== undefined) {
                    document.getElementById('current-time-in-service').value = newTimeInService;
                    document.getElementById('current-time-in-service-display').textContent = `Time in Service: ${newTimeInService}`;
                    previousTimeInServiceHours = newTimeInService;
                }
                markUpdatedNow('block-time-updated', 'flightlog');
                markUpdatedNow('time-in-service-updated', 'flightlog');

                // A delete can shift other rows' Out/In too (see
                // refreshLogbookRows) -- rebuild the whole table instead of
                // just removing this one row.
                await refreshLogbookRows();
            } catch (error) {
                console.error('Error deleting log:', error);
            }
        });
    }

    // force=true skips the manual-entry accuracy check (server-side) --
    // used to resubmit after the user confirms "Add it anyway?".
    async function submitAddFlightLog(force) {
        // Read raw strings first so an empty input stays null (not 0), letting
        // the server enforce the "complete pair" rule cleanly.
        const rawBlockTimeIn  = parseFloat(parseFloat(document.getElementById('blockTimeIn').value.trim()).toFixed(2));
        const rawBlockTimeOut = parseFloat(parseFloat(document.getElementById('blockTimeOut').value.trim()).toFixed(2));
        const rawTimeInServiceIn   = parseFloat(parseFloat(document.getElementById('timeInServiceIn').value.trim()).toFixed(2));
        const rawTimeInServiceOut  = parseFloat(parseFloat(document.getElementById('timeInServiceOut').value.trim()).toFixed(2));
        const numOrNull = (s) => (s === '' || isNaN(parseFloat(s))) ? null : parseFloat(s);

        const data = {
            fromAirport: document.getElementById('fromAirport').value,
            toAirport: document.getElementById('toAirport').value,
            blockTimeOut: numOrNull(rawBlockTimeOut),
            blockTimeIn:  numOrNull(rawBlockTimeIn),
            timeInServiceOut:  numOrNull(rawTimeInServiceOut),
            timeInServiceIn:   numOrNull(rawTimeInServiceIn),
            blockTimeStart: datetimeLocalToIso(document.getElementById('blockTimeStart').value),
            blockTimeEnd: datetimeLocalToIso(document.getElementById('blockTimeEnd').value),
            timeInServiceStart: datetimeLocalToIso(document.getElementById('timeInServiceStart').value),
            timeInServiceEnd: datetimeLocalToIso(document.getElementById('timeInServiceEnd').value),
            source: csvPrefilled ? 'csv' : 'manual',
        };

        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');

        console.log("!!!NEW flight log row DATA, POST request -->", data);

        try {
            const response = await axios.post(`/addflightlog?force=${force}`, data, {
                headers: {
                    [csrfHeader]: csrfToken,
                    'Content-Type': 'application/json'
                }
            });

            const log = response.data;  // Assuming response.data is the saved log
            console.log("Response: ", log);

            // Recomputing the chain can shift OTHER rows' Out/In/timestamps
            // too (a backfilled flight pushes everything after it), so the
            // whole table is rebuilt from the server's current state rather
            // than just inserting the one row we added.
            await refreshLogbookRows();

            // Update hours display from backend-calculated totals
            if (log.newBlockTime !== undefined && log.newBlockTime !== null) {
                document.getElementById('current-block-time').value = log.newBlockTime;
                document.getElementById('current-block-time-display').textContent = `Block Time: ${log.newBlockTime}`;
                previousBlockTimeHours = log.newBlockTime;
            }
            if (log.newTimeInService !== undefined && log.newTimeInService !== null) {
                document.getElementById('current-time-in-service').value = log.newTimeInService;
                document.getElementById('current-time-in-service-display').textContent = `Time in Service: ${log.newTimeInService}`;
                previousTimeInServiceHours = log.newTimeInService;
            }
            markUpdatedNow('block-time-updated', 'flightlog');
            markUpdatedNow('time-in-service-updated', 'flightlog');

            // Clear inputs
            document.getElementById('fromAirport').value = '';
            document.getElementById('toAirport').value = '';
            document.getElementById('blockTimeIn').value = '';
            document.getElementById('blockTimeOut').value = '';
            document.getElementById('timeInServiceIn').value = '';
            document.getElementById('timeInServiceOut').value = '';
            document.getElementById('blockTimeStart').value = '';
            document.getElementById('blockTimeEnd').value = '';
            document.getElementById('timeInServiceStart').value = '';
            document.getElementById('timeInServiceEnd').value = '';
            csvPrefilled = false;
        } catch (error) {
            const status = error?.response?.status;
            const responseData = error?.response?.data;

            // 409 = a manual reading that doesn't line up with nearby flights
            // (HoursService.checkAccuracy) -- not a hard error, ask first.
            if (status === 409 && responseData?.inaccurate) {
                const confirmed = await showConfirm(responseData.message, 'Add anyway');
                if (confirmed) await submitAddFlightLog(true);
                return;
            }

            // 400 = validation error from the server (e.g. partial pair, negative duration).
            // Keep the user's typed values intact so they can correct and retry —
            // the whole point of this code path is "don't hurt the user."
            const serverMsg = responseData?.message;
            if (status === 400 && serverMsg) {
                showToast(serverMsg, 'error');
            } else {
                console.error('Error adding log:', error);
                showToast('Failed to add flight log.', 'error');
            }
        }
    }

    document.getElementById('add-log-button').addEventListener('click', () => submitAddFlightLog(false));

});

    // Delete log row (server-rendered rows -- this listener block runs at
    // global scope, outside the DOMContentLoaded closure above, so it can't
    // call the closure-scoped refreshLogbookRows/wireDeleteLogButton; it
    // does its own equivalent refetch-and-rebuild using the global
    // buildLogRowHtml instead.)
    document.querySelectorAll('.delete-log-icon').forEach(function wireDeleteLogIconGlobal(button) {
        button.addEventListener('click', async () => {
            const confirmed = await showConfirm('Delete this flight log entry?', 'Delete');
            if (!confirmed) return;
            const row = button.closest('tr');
            const id = row.dataset.id;
            const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
            try {
                const deleteResponse = await axios.delete(`/deleteflightlog/${id}`, {
                    headers: { [csrfHeader]: csrfToken }
                });
                allSuggestionsStale = true; // deleting can reset a suggestion back to pending
                console.log(`Log ${id} deleted`);
                const { newBlockTime, newTimeInService } = deleteResponse.data;
                if (newBlockTime !== undefined) {
                    document.getElementById('current-block-time').value = newBlockTime;
                    document.getElementById('current-block-time-display').textContent = `Block Time: ${newBlockTime}`;
                    previousBlockTimeHours = newBlockTime;
                }
                if (newTimeInService !== undefined) {
                    document.getElementById('current-time-in-service').value = newTimeInService;
                    document.getElementById('current-time-in-service-display').textContent = `Time in Service: ${newTimeInService}`;
                    previousTimeInServiceHours = newTimeInService;
                }
                markUpdatedNow('block-time-updated', 'flightlog');
                markUpdatedNow('time-in-service-updated', 'flightlog');
                updateAllTimeLeft();
                updateAddRowTimeLeft();

                // A delete can shift other rows' Out/In too now that
                // HoursService.recomputeChain keeps CSV/AeroAPI rows
                // chronologically accurate -- rebuild the whole table
                // instead of just removing this one row.
                const logsResponse = await axios.get('/flightlogs', { headers: { [csrfHeader]: csrfToken } });
                document.querySelectorAll('#logbook-body .log-row').forEach(r => r.remove());
                const tbody = document.getElementById('logbook-body');
                const addLogRow = document.querySelector('.add-log-row');
                logsResponse.data.forEach(log => {
                    const newRow = document.createElement('tr');
                    newRow.className = 'log-row';
                    newRow.dataset.id = log.id;
                    newRow.innerHTML = buildLogRowHtml(log);
                    if (addLogRow) tbody.insertBefore(newRow, addLogRow); else tbody.appendChild(newRow);
                    wireDeleteLogIconGlobal(newRow.querySelector('.delete-log-icon'));
                });
            } catch (error) {
                console.error('Error deleting log:', error);
            }
        });
    });

    

    document.getElementById('upload-csv-btn').addEventListener('click', function() {
        document.getElementById('csv-file-input').click();
    });

    document.getElementById('csv-file-input').addEventListener('change', uploadCSV);

    function uploadCSV() {
        const csvfile = document.getElementById('csv-file-input').files[0];
        const formData = new FormData();
        formData.append('csvfile', csvfile)

        const csrfToken = document.querySelector('meta[name="_csrf"]').content;
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;

        showToast('Uploading CSV...', 'info');

        axios.post('/logbook/upload-csv', formData, {
            headers: {
                [csrfHeader]: csrfToken
            }
        }).then(response => {
            document.getElementById('csv-file-input').value = '';

            const data = response.data;
            showToast("CSV parsed successfully", 'info');

            document.getElementById("blockTimeOut").value = data.blockTimeOut ?? '';
            document.getElementById("blockTimeIn").value = data.blockTimeIn ?? '';
            document.getElementById("timeInServiceOut").value = data.timeInServiceOut ?? '';
            document.getElementById("timeInServiceIn").value = data.timeInServiceIn ?? '';
            document.getElementById("blockTimeStart").value = isoToDatetimeLocal(data.blockTimeStart);
            document.getElementById("blockTimeEnd").value = isoToDatetimeLocal(data.blockTimeEnd);
            document.getElementById("timeInServiceStart").value = isoToDatetimeLocal(data.timeInServiceStart);
            document.getElementById("timeInServiceEnd").value = isoToDatetimeLocal(data.timeInServiceEnd);
            csvPrefilled = true;

            if (data.warning) {
                showToast(data.warning, 'error');
            } else {
                showToast('Flight log prefilled', 'info');
            }

        }).catch(error => {
            const errorData = error.response?.data;
            const errorMessage = error.response?.data?.error || 'Upload failed';
            showToast(errorMessage, 'error');

            console.log(errorData?.error);        // "Could not detect airtime or block time"
            console.log(errorData?.blockStart);   // ISO instant string
            console.log(errorData?.blockEnd);
            console.log(errorData?.airborneStart);
            console.log(errorData?.airborneEnd);

            document.getElementById('csv-file-input').value = '';
        });

    }


// ── Maintenance alerts settings (docs/ALERTS_SPEC.md) ────────────────────────
// Standalone DOMContentLoaded block: talks to /alerts/* and is independent of
// the large closure above.
document.addEventListener('DOMContentLoaded', () => {
    const $ = (id) => document.getElementById(id);
    const enabledBox   = $('alerts-enabled');
    if (!enabledBox) return; // alerts UI not on this page

    const readiness    = $('alerts-readiness');
    const checkHour    = $('alerts-check-hour');
    const leadDays     = $('alerts-lead-days');
    const leadHours    = $('alerts-lead-hours');
    const renudgeDays  = $('alerts-renudge-days');
    const saveSchedule = $('alerts-save-schedule');
    const sendNowBtn   = $('alerts-send-now');
    const sendNowMsg   = $('alerts-send-now-result');
    const chanSel      = $('alert-recipient-channel');
    const destInput    = $('alert-recipient-destination');
    const labelInput   = $('alert-recipient-label');
    const addBtn       = $('alert-recipient-add');
    const addError     = $('alert-recipient-error');
    const listEl       = $('alert-recipient-list');

    function csrf() {
        const t = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
        const h = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
        return h ? { [h]: t } : {};
    }
    const jsonHeaders = () => ({ 'Content-Type': 'application/json', ...csrf() });

    async function loadPrefs() {
        try {
            const r = await fetch('/alerts/preferences');
            if (!r.ok) return;
            const p = await r.json();
            enabledBox.checked = !!p.enabled;
            checkHour.value    = p.checkHour;
            leadDays.value     = p.leadTimeDays;
            leadHours.value    = p.leadTimeHours;
            renudgeDays.value  = p.overdueRenudgeDays;
            const missing = (p.readiness && p.readiness.missing) || [];
            readiness.textContent = missing.length
                ? 'Before turning on: ' + missing.join('; ')
                : 'Ready to turn on.';
        } catch (e) { /* leave defaults */ }
    }

    function statusBadge(s) {
        const map = { PENDING: 'Pending', ACCEPTED: 'Accepted', DECLINED: 'Declined', EXPIRED: 'Expired' };
        return map[s] || s;
    }

    async function loadRecipients() {
        try {
            const r = await fetch('/alerts/recipients');
            if (!r.ok) return;
            const { recipients } = await r.json();
            listEl.innerHTML = '';
            if (!recipients.length) {
                listEl.innerHTML = '<li class="alert-recipient-empty">No recipients yet.</li>';
                return;
            }
            for (const rec of recipients) {
                const li = document.createElement('li');
                li.dataset.id = rec.id;
                const who = rec.label ? `${rec.label} — ${rec.destination}` : rec.destination;
                li.innerHTML =
                    `<span class="alert-recipient-who">${who}</span>` +
                    `<span class="alert-recipient-status status-${rec.status.toLowerCase()}">${statusBadge(rec.status)}</span>` +
                    `<button type="button" class="alert-recipient-resend no-print">Resend</button>` +
                    `<button type="button" class="alert-recipient-remove no-print">Remove</button>`;
                listEl.appendChild(li);
            }
        } catch (e) { /* ignore */ }
    }

    async function savePrefs(extra) {
        const body = {
            enabled: enabledBox.checked,
            checkHour: Number(checkHour.value),
            leadTimeDays: Number(leadDays.value),
            leadTimeHours: Number(leadHours.value),
            overdueRenudgeDays: Number(renudgeDays.value),
            ...extra,
        };
        const r = await fetch('/alerts/preferences', {
            method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(body),
        });
        if (r.ok) { const p = await r.json(); enabledBox.checked = !!p.enabled; }
        return r.ok;
    }

    enabledBox.addEventListener('change', async () => {
        const ok = await savePrefs();
        if (typeof showToast === 'function') {
            showToast(ok ? (enabledBox.checked ? 'Alerts turned on' : 'Alerts turned off') : 'Could not save', ok ? 'info' : 'error');
        }
        if (!ok) enabledBox.checked = !enabledBox.checked;
    });

    saveSchedule.addEventListener('click', async () => {
        const ok = await savePrefs();
        if (typeof showToast === 'function') showToast(ok ? 'Alert schedule saved' : 'Could not save', ok ? 'info' : 'error');
    });

    sendNowBtn.addEventListener('click', async () => {
        sendNowMsg.textContent = 'Sending…';
        try {
            const r = await fetch('/alerts/send-now', { method: 'POST', headers: csrf() });
            const d = await r.json();
            sendNowMsg.textContent = r.ok ? `Sent to ${d.sent} recipient(s).` : (d.error || 'Failed.');
        } catch (e) { sendNowMsg.textContent = 'Failed.'; }
    });

    addBtn.addEventListener('click', async () => {
        addError.textContent = '';
        const params = new URLSearchParams();
        params.set('channel', chanSel.value);
        params.set('destination', destInput.value.trim());
        if (labelInput.value.trim()) params.set('label', labelInput.value.trim());
        try {
            const r = await fetch('/alerts/recipients', { method: 'POST', headers: csrf(), body: params });
            const d = await r.json();
            if (!r.ok) { addError.textContent = d.error || 'Could not add.'; return; }
            destInput.value = ''; labelInput.value = '';
            loadRecipients();
        } catch (e) { addError.textContent = 'Could not add.'; }
    });

    listEl.addEventListener('click', async (ev) => {
        const li = ev.target.closest('li[data-id]');
        if (!li) return;
        const id = li.dataset.id;
        if (ev.target.classList.contains('alert-recipient-remove')) {
            const r = await fetch('/alerts/recipients/' + id, { method: 'DELETE', headers: csrf() });
            if (r.ok) loadRecipients();
        } else if (ev.target.classList.contains('alert-recipient-resend')) {
            const r = await fetch('/alerts/recipients/' + id + '/resend', { method: 'POST', headers: csrf() });
            if (r.ok && typeof showToast === 'function') showToast('Confirmation resent', 'info');
            loadRecipients();
        }
    });

    const settingsBtn = document.getElementById('settings');
    if (settingsBtn) settingsBtn.addEventListener('click', () => { loadPrefs(); loadRecipients(); });
    loadPrefs();
    loadRecipients();
});
