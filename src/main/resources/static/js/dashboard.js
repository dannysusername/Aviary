console.log('dashboard.js loaded');

let timeout;
let userInfoTimeout;
let hoursTimeout; // For debouncing hours updates
let previousHobbsHours = document.getElementById('current-hobbs')?.value || 0;
let previousTachHours = document.getElementById('current-tach')?.value || 0;


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
        let value = '';
        if (dateInput) value += dateInput.value; //if date input then append to value
        if (textInput) value += value ? ` ${textInput.value}` : textInput.value; //if text input append to value 
        data[field] = value.trim(); // set data[field] to the value trimmed
    });

    const timeLeftSpan = row.querySelector('td:nth-child(7) .time-left');
    if (timeLeftSpan) {
        const currentTachHoursInput = document.getElementById('current-tach');
        const currentTachHours = currentTachHoursInput ? parseFloat(currentTachHoursInput.value) || 0 : 0;
        const dueDate = data.dueDate || '';
        setTimeLeftText(timeLeftSpan, calculateTimeLeft(dueDate, currentTachHours));
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
            console.log(data);
            // Optionally add a status indicator next to the input if needed

            // NEW: Update the adjacent print-only span with the new value
            const printSpan = input.nextElementSibling;
            if (printSpan && printSpan.classList.contains('print-only')) {
                printSpan.textContent = input.value;
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
        row.setAttribute('data-lastDone', lastDone || '');
        row.setAttribute('data-dueDate',  dueDate  || '');
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
    container.querySelectorAll('.add-type').forEach(btn => btn.textContent = '+');
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
        if (calBtn) calBtn.textContent = '-';
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
        if (clkBtn) clkBtn.textContent = '-';
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
            if (dropdown.style.display === 'block') {
                setTimeout(() => document.addEventListener('click', closeDropdownOutside, { once: true }), 0);
            }
        } else {
            console.error('Dropdown options not found for:', event.target);
        }
    }
});


function closeDropdownOutside(event) {
    if (!event.target.closest('.custom-dropdown')) {
        document.querySelectorAll('.dropdown-options').forEach(dropdown => dropdown.style.display = 'none');
    }
}

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

function calculateTimeLeft(dueDateStr, currentTachHours) {
    if (!dueDateStr) return 'N/A';

    const now = new Date();
    let output = '';

    // Parse dueDateStr
    const dueDateParts = dueDateStr.split(' ');
    const dueDateDate = dueDateParts[0]?.match(/^\d{4}-\d{2}-\d{2}$/) ? dueDateParts[0] : null;
    // Hours part: whole or decimal (e.g. "100" or "100.5" or ".5"). Exclude the date part if present.
    const dueDateTime = dueDateParts.find(part => part !== dueDateDate && /^\d*\.?\d+$/.test(part)) || null;

    // Calculate days if dueDate has a calendar date
    if (dueDateDate) {
        const dueDate = new Date(dueDateDate + 'T00:00:00');
        const timeDiff = dueDate - now;
        const daysLeft = Math.ceil(timeDiff / (1000 * 60 * 60 * 24));
        output += daysLeft < 0 ? `${Math.abs(daysLeft)} days overdue` : `${daysLeft} days left`;
    }

    // Calculate hours if dueDate has a clock value
    if (dueDateTime) {
        const dueDateHours = parseFloat(dueDateTime);
        if (!isNaN(dueDateHours) && !isNaN(currentTachHours)) {
            const hoursLeft = Math.round((dueDateHours - currentTachHours) * 10) / 10;
            const hoursText = hoursLeft < 0 ? `${Math.abs(hoursLeft)} hours overdue` : `${hoursLeft} hours left`;
            output += output ? `\n${hoursText}` : hoursText;
        }
    }

    console.log("calculateTimeLeft OUTPUT--->" + output);

    return output || 'N/A';
}

function setTimeLeftText(cell, text) {
    cell.textContent = text;
    cell.style.color = text.includes('overdue') ? 'red' : 'black';
}

// Function to update all Time Left cells in real-time
function updateAllTimeLeft() {
    const currentTachHoursInput = document.getElementById('current-tach');
    const currentTachHours = currentTachHoursInput ? parseFloat(currentTachHoursInput.value) || 0 : 0;

    document.querySelectorAll('.auto-save-row').forEach(row => {
        const dueDateContainer = row.querySelector('td:nth-child(6) .input-with-dropdown');
        const timeLeftCell = row.querySelector('td:nth-child(7) .time-left');
        if (timeLeftCell) {
            const dueDateDate = dueDateContainer.querySelector('input[type="date"]');
            const dueDateText = dueDateContainer.querySelector('input[type="text"].extra-input');
            let dueDateStr = '';
            if (dueDateDate) dueDateStr += dueDateDate.value;
            if (dueDateText) dueDateStr += dueDateStr ? ` ${dueDateText.value}` : dueDateText.value;
            console.log('Due Date String:', dueDateStr);
            const timeLeftText = calculateTimeLeft(dueDateStr, currentTachHours);
            console.log('Calculated Time Left:', timeLeftText);
            setTimeLeftText(timeLeftCell, timeLeftText);
        }
        
    });
}

function updateAddRowTimeLeft() {
    const currentTachHoursInput = document.getElementById('current-tach');
    const currentTachHours = currentTachHoursInput ? parseFloat(currentTachHoursInput.value) || 0 : 0;
    const addRow = document.querySelector('.add-row');
    const dueDateContainer = addRow.querySelector('td:nth-child(6) .input-with-dropdown');
    const timeLeftCell = addRow.querySelector('td:nth-child(7) .time-left');

    if (timeLeftCell) {
        const dueDateDate = dueDateContainer.querySelector('input[type="date"]');
        const dueDateText = dueDateContainer.querySelector('input[type="text"].extra-input');
        let dueDateStr = '';
        if (dueDateDate) dueDateStr += dueDateDate.value;
        if (dueDateText) dueDateStr += dueDateStr ? ` ${dueDateText.value}` : dueDateText.value;

        setTimeLeftText(timeLeftCell, calculateTimeLeft(dueDateStr, currentTachHours));
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
    renderUpdatedFromData('hobbs-updated');
    renderUpdatedFromData('tach-updated');
    
    document.querySelectorAll('.user-info-input').forEach(input => {
        input.addEventListener('input', () => autoSaveUserInfo(input));
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
                    sibling.style.display = 'block';
                    setTimeout(() => document.addEventListener('click', closeTypeDropdowns, { once: true }), 0);
                }
            } else if (sibling && sibling.classList.contains('dropdown-options')) {
                // Description custom dropdown
                const isOpen = sibling.style.display === 'block';
                document.querySelectorAll('.dropdown-options').forEach(d => d.style.display = 'none');
                if (!isOpen) {
                    sibling.style.display = 'block';
                    setTimeout(() => document.addEventListener('click', closeDropdownOutside, { once: true }), 0);
                }
            }

        } else if (event.target.classList.contains('add-type')) {
            const button = event.target;
            const type = button.getAttribute('data-type');
            const container = button.closest('.input-with-dropdown');
            const tr = button.closest('tr');
            const isAddMode = button.textContent === '+';
    
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
                            updateAddRowHiddenInputs();
                            updateAddRowTimeLeft();
                        }
                    };
                    container.insertBefore(newInput, container.querySelector('.trigger-dropdown'));
                    button.textContent = '-';
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
                            updateAddRowHiddenInputs();
                            updateAddRowTimeLeft();
                        }
                    };
                    container.insertBefore(newInput, container.querySelector('.trigger-dropdown'));
                    button.textContent = '-';
                }
            } else {
                // Removing an existing input
                if (type === 'calendar' && existingDate) {
                    existingDate.remove();
                    button.textContent = '+';
                    if (tr.classList.contains('auto-save-row')) {
                        autoSave(button); // Trigger save for sortable rows
                    } else {
                        updateAddRowHiddenInputs();
                        updateAddRowTimeLeft();
                    }
                } else if (type === 'clock' && existingText) {
                    existingText.remove();
                    button.textContent = '+';
                    if (tr.classList.contains('auto-save-row')) {
                        autoSave(button); // Trigger save for sortable rows
                    } else {
                        updateAddRowHiddenInputs();
                        updateAddRowTimeLeft();
                    }
                }
            }
    
            // Hide the dropdown after action
            button.closest('.type-dropdown').style.display = 'none';
        } else if (event.target.classList.contains('edit-hours-btn')) {
            document.getElementById('add-tach-time').value = '';
            document.getElementById('add-hobbs-time').value = '';
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
        fallbackTolerance: 4,
        touchStartThreshold: 4,
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

    let previousHobbsHours = 0;
    let previousTachHours = 0;
    let hoursTimeout;

    const currentHobbsHoursInput = document.getElementById('current-hobbs');
    const currentTachHoursInput = document.getElementById('current-tach');

    if (currentHobbsHoursInput) {
        previousHobbsHours = currentHobbsHoursInput.value || 0;
        currentHobbsHoursInput.addEventListener('input', function() {
            updateAllTimeLeft();
            updateAddRowTimeLeft();
            clearTimeout(hoursTimeout);
            const newHobbsHours = this.value.trim();
            if (newHobbsHours === '' || isNaN(parseFloat(newHobbsHours))) {
                return;
            }
            hoursTimeout = setTimeout(() => {
                const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
                const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
                const params = new URLSearchParams();
                params.append('newHobbsTime', parseFloat(newHobbsHours));
                axios.post('/updateHours', params, {
                    headers: { [csrfHeader]: csrfToken }
                })
                .then(response => {
                    if (response.data.status === 'success') {
                        previousHobbsHours = newHobbsHours;
                        document.getElementById('current-hobbs-display').textContent = `Hobbs Time: ${newHobbsHours}`;
                        markUpdatedNow('hobbs-updated', 'manual');
                        console.log('Hobbs hours updated successfully:', response.data.newHobbs);
                    } else {
                        this.value = previousHobbsHours;
                        document.getElementById('current-hobbs-display').textContent = `Hobbs Time: ${previousHobbsHours}`;
                        console.error('Failed to update hobbs hours:', response.data.message);
                        showToast('Failed to update Hobbs hours.', 'error');
                    }
                })
                .catch(error => {
                    console.error('Error updating hours:', error.response ? error.response.data : error);
                    this.value = previousHobbsHours;
                    document.getElementById('current-hobbs-display').textContent = `Hobbs Time: ${previousHobbsHours}`;
                    showToast('Failed to update Hobbs hours.', 'error');
                });
            }, 500);
        });
    }

    if (currentTachHoursInput) {
        previousTachHours = currentTachHoursInput.value || 0;
        currentTachHoursInput.addEventListener('input', function() {
            updateAllTimeLeft();
            updateAddRowTimeLeft();
            clearTimeout(hoursTimeout);
            const newTachHours = this.value.trim();
            if (newTachHours === '' || isNaN(parseFloat(newTachHours))) {
                return;
            }
            hoursTimeout = setTimeout(() => {
                const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
                const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
                const params = new URLSearchParams();
                params.append('newTachTime', parseFloat(newTachHours));
                axios.post('/updateHours', params, {
                    headers: { [csrfHeader]: csrfToken }
                })
                .then(response => {
                    if (response.data.status === 'success') {
                        previousTachHours = newTachHours;
                        document.getElementById('current-tach-display').textContent = `Tach Time: ${newTachHours}`;
                        markUpdatedNow('tach-updated', 'manual');
                        console.log('Tach hours updated successfully:', response.data.newTach);
                    } else {
                        this.value = previousTachHours;
                        document.getElementById('current-tach-display').textContent = `Tach Time: ${previousTachHours}`;
                        console.error('Failed to update tach hours:', response.data.message);
                        showToast('Failed to update Tach hours.', 'error');
                    }
                })
                .catch(error => {
                    console.error('Error updating hours:', error.response ? error.response.data : error);
                    this.value = previousTachHours;
                    document.getElementById('current-tach-display').textContent = `Tach Time: ${previousTachHours}`;
                    showToast('Failed to update Tach hours.', 'error');
                });
            }, 500);
        });
    }

    document.querySelectorAll('.auto-save-row').forEach(row => {
        ['lastDone', 'dueDate'].forEach((field, index) => {
            const container = row.querySelector(`td:nth-child(${index === 0 ? 5 : 6}) .input-with-dropdown`);
            const savedValue = row.getAttribute(`data-${field}`) || '';
            if (savedValue) {
                const parts = savedValue.split(' ');
                let datePart = null;
                let textPart = null;

                if (parts[0] && parts[0].match(/^\d{4}-\d{2}-\d{2}$/)) {
                    datePart = parts[0];
                    if (parts.length > 1) {
                        textPart = parts.slice(1).join(' ');
                    }
                } else {
                    textPart = savedValue;
                }

                if (datePart) {
                    const dateInput = document.createElement('input');
                    dateInput.type = 'date';
                    dateInput.name = `${field}_date`;
                    dateInput.className = 'extra-input';
                    dateInput.value = datePart;
                    dateInput.oninput = () => autoSave(dateInput);
                    container.insertBefore(dateInput, container.querySelector('.trigger-dropdown'));
                    container.querySelector('.add-type[data-type="calendar"]').textContent = '-';
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
                    container.querySelector('.add-type[data-type="clock"]').textContent = '-';
                }
            }
        });
    });

    updateAllTimeLeft();
    refreshAllCompleteButtons();

    function closeTypeDropdowns(event) {
        if (!event.target.closest('.input-with-dropdown')) {
            document.querySelectorAll('.type-dropdown').forEach(dropdown => {
                dropdown.style.display = 'none';
            });
        }
    }

    const addRowItemTextarea = document.querySelector('.add-row textarea[name="item"]');
    if (addRowItemTextarea) {
        setTextareaMinHeight(addRowItemTextarea);
        addRowItemTextarea.addEventListener('input', () => {
            setTextareaMinHeight(addRowItemTextarea);
        });
    }

    function updateAddRowHiddenInputs() {
        const lastDoneTd = document.getElementById('lastDoneHidden').parentElement;
        const dueDateTd = document.getElementById('dueDateHidden').parentElement;

        const lastDoneContainer = lastDoneTd.querySelector('.input-with-dropdown');
        const dueDateContainer = dueDateTd.querySelector('.input-with-dropdown');

        if (lastDoneContainer) {
            const lastDoneDate = lastDoneContainer.querySelector('input[type="date"]');
            const lastDoneText = lastDoneContainer.querySelector('input[type="text"].extra-input');
            let lastDoneValue = '';
            if (lastDoneDate) lastDoneValue += lastDoneDate.value;
            if (lastDoneText) lastDoneValue += lastDoneValue ? ` ${lastDoneText.value}` : lastDoneText.value;
            document.getElementById('lastDoneHidden').value = lastDoneValue.trim();
        }

        if (dueDateContainer) {
            const dueDateDate = dueDateContainer.querySelector('input[type="date"]');
            const dueDateText = dueDateContainer.querySelector('input[type="text"].extra-input');
            let dueDateValue = '';
            if (dueDateDate) dueDateValue += dueDateDate.value;
            if (dueDateText) dueDateValue += dueDateValue ? ` ${dueDateText.value}` : dueDateText.value;
            document.getElementById('dueDateHidden').value = dueDateValue.trim();
        }
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
        const itemInput = document.getElementById('itemInput').querySelector('textarea');
        const titleInput = document.getElementById('titleInput').querySelector('input');
        const isTitle = isTitleHidden.value === 'true';
        const item = isTitle ? titleInput.value : itemInput.value;

        if (!item) {
            showToast('Please enter an item or title before submitting.', 'error');
            return;
        }

        const description = document.querySelector('.add-row .custom-dropdown input[name="description"]').value;
        const lastDone = document.getElementById('lastDoneHidden').value;
        const dueDate = document.getElementById('dueDateHidden').value;
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
            lastDone: lastDone,
            dueDate: dueDate,
            timeLeft: timeLeft,
            ajax: 'true'
        };

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
                newRow.setAttribute('data-lastDone', newRowData.lastDone);
                newRow.setAttribute('data-dueDate', newRowData.dueDate);
                newRow.setAttribute('data-cycle', newRowData.cycle);
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
                            <div class="type-option"><span>Calendar</span><button class="add-type" data-type="calendar">+</button></div>
                            <div class="type-option"><span>Clock</span><button class="add-type" data-type="clock">+</button></div>
                        </div>
                    </div>
                    <span class="print-only">${newRowData.lastDone}</span>
                </td>
                <td data-label="Due Date">
                    <div class="input-with-dropdown no-print">
                        <i class="fa-solid fa-chevron-down trigger-dropdown"></i>
                        <div class="type-dropdown" style="display: none;">
                            <div class="type-option"><span>Calendar</span><button class="add-type" data-type="calendar">+</button></div>
                            <div class="type-option"><span>Clock</span><button class="add-type" data-type="clock">+</button></div>
                        </div>
                    </div>
                    <span class="print-only">${newRowData.dueDate}</span>
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
                    // Handle lastDone and dueDate inputs (existing code)
                    ['lastDone', 'dueDate'].forEach((field, index) => {
                        const value = newRowData[field];
                        if (value) {
                            const container = newRow.querySelector(`td:nth-child(${index === 0 ? 5 : 6}) .input-with-dropdown`);
                            const parts = value.split(' ');
                            let datePart = null;
                            let textPart = null;

                            if (parts[0].match(/^\d{4}-\d{2}-\d{2}$/)) {
                                datePart = parts[0];
                                if (parts.length > 1) {
                                    textPart = parts.slice(1).join(' ');
                                }
                            } else {
                                textPart = value;
                            }

                            if (datePart) {
                                const dateInput = document.createElement('input');
                                dateInput.type = 'date';
                                dateInput.className = 'extra-input';
                                dateInput.value = datePart;
                                dateInput.oninput = () => autoSave(dateInput);
                                container.insertBefore(dateInput, container.querySelector('.trigger-dropdown'));
                                container.querySelector('.add-type[data-type="calendar"]').textContent = '-';
                            }

                            if (textPart) {
                                const textInput = document.createElement('input');
                                textInput.type = 'text';
                                textInput.className = 'extra-input';
                                textInput.value = textPart;
                                textInput.oninput = () => autoSave(textInput);
                                container.insertBefore(textInput, container.querySelector('.trigger-dropdown'));
                                container.querySelector('.add-type[data-type="clock"]').textContent = '-';
                            }
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
                document.querySelectorAll('.add-row .add-type').forEach(btn => btn.textContent = '+');
                document.querySelector('.add-row .time-left').textContent = '';
                document.getElementById('lastDoneHidden').value = '';
                document.getElementById('dueDateHidden').value = '';

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

    function closeTypeDropdowns(event) {
        if (!event.target.closest('.input-with-dropdown')) {
            document.querySelectorAll('.type-dropdown').forEach(dropdown => {
                dropdown.style.display = 'none';
            });
        }
    }

    document.addEventListener('click', function(event) {
        
        if (event.target.id === 'add-hobbs-btn') {
            const hobbsTimeToAdd = document.getElementById('add-hobbs-time').value.trim();
            addHobbsHours(hobbsTimeToAdd);

        } else if (event.target.id === 'add-tach-btn') {
            const tachTimeToAdd = document.getElementById('add-tach-time').value.trim();
            addTachHours(tachTimeToAdd);
        }
    });
    
    async function addHobbsHours(hobbsTimeToAdd) {
        if (hobbsTimeToAdd && !isNaN(hobbsTimeToAdd)) {
            await updateHours({ hobbsTimeToAdd: parseFloat(hobbsTimeToAdd) });
        }
    }
    
    // Updated addTachHours (now uses updateHours)
    async function addTachHours(tachTimeToAdd) {
        if (tachTimeToAdd && !isNaN(tachTimeToAdd)) {
            await updateHours({ tachTimeToAdd: parseFloat(tachTimeToAdd) });
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
                const { newHobbs, newTach } = response.data;
    
                if (newHobbs !== undefined) {
                    document.getElementById('current-hobbs').value = newHobbs;
                    document.getElementById('current-hobbs-display').textContent = `Hobbs Time: ${newHobbs}`;
                    previousHobbsHours = newHobbs;
                    console.log('Hobbs updated successfully:', newHobbs);
                }
    
                if (newTach !== undefined) {
                    document.getElementById('current-tach').value = newTach;
                    document.getElementById('current-tach-display').textContent = `Tach Time: ${newTach}`;
                    previousTachHours = newTach;
                    console.log('Tach updated successfully:', newTach);
                }
    
                if ('hobbsTimeToAdd' in updateParams) markUpdatedNow('hobbs-updated', 'manual');
                if ('tachTimeToAdd' in updateParams) markUpdatedNow('tach-updated', 'manual');

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
        const currentTachHoursInput = document.getElementById('current-tach');
        const currentTachHours = currentTachHoursInput ? parseFloat(currentTachHoursInput.value) || 0 : 0;

        // Build array of arrays (aoa) for the sheet
        let aoa = [
            ["Current Hours", currentTachHours], // Row 1: Reference for hour calcs (user can update B1 in Excel)
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
                const initialTimeLeft = calculateTimeLeft(dueDate, currentTachHours);

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

    // NEW: Print button listener
    const printBtn = document.getElementById('print-dashboard');
    if (printBtn) {
        printBtn.addEventListener('click', printDashboard);
    }

    // NEW: Function to handle printing
    function printDashboard() {
        // Update all time left values (assuming you have updateAllTimeLeft() from existing code)
        updateAllTimeLeft();
        updateAddRowTimeLeft();  // If applicable, though add-row is hidden

        // Update My Hours print-only span
        const currentTachHours = document.getElementById('current-tach').value || '0';
        

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

        // Trigger browser print dialog
        window.print();
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


    // NEW: Add log row via AJAX
    document.getElementById('add-log-button').addEventListener('click', async () => {  // Note: made async for await if needed
        // Read raw strings first so an empty input stays null (not 0), letting
        // the server enforce the "complete pair" rule cleanly.
        const rawHobbsIn  = document.getElementById('hobbsIn').value.trim();
        const rawHobbsOut = document.getElementById('hobbsOut').value.trim();
        const rawTachIn   = document.getElementById('tachIn').value.trim();
        const rawTachOut  = document.getElementById('tachOut').value.trim();
        const numOrNull = (s) => (s === '' || isNaN(parseFloat(s))) ? null : parseFloat(s);

        const data = {
            fromAirport: document.getElementById('fromAirport').value,
            toAirport: document.getElementById('toAirport').value,
            hobbsIn:  numOrNull(rawHobbsIn),
            hobbsOut: numOrNull(rawHobbsOut),
            tachIn:   numOrNull(rawTachIn),
            tachOut:  numOrNull(rawTachOut)
        };
    
        const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
    
        console.log("!!!NEW flight log row DATA, POST request -->", data); 
    
        try {
            const response = await axios.post('/addflightlog', data, {
                headers: { 
                    [csrfHeader]: csrfToken,
                    'Content-Type': 'application/json'
                }
            });
    
            const log = response.data;  // Assuming response.data is the saved log
            console.log("Response: ", log);

            const newRow = document.createElement('tr');
            newRow.className = 'log-row';
            newRow.dataset.id = log.id;
            newRow.innerHTML = `
                <td data-label="From"><span class="print-only">${log.fromAirport || ''}</span><input type="text" name="fromAirport" class="no-print" value="${log.fromAirport || ''}" readonly></td>
                <td data-label="To"><span class="print-only">${log.toAirport || ''}</span><input type="text" name="toAirport" class="no-print" value="${log.toAirport || ''}" readonly></td>
                <td data-label="Hobbs Out"><span class="print-only">${log.hobbsOut || ''}</span><input type="number" name="hobbsOut" class="no-print" value="${log.hobbsOut || ''}" readonly step="0.1"></td>
                <td data-label="Hobbs In"><span class="print-only">${log.hobbsIn || ''}</span><input type="number" name="hobbsIn" class="no-print" value="${log.hobbsIn || ''}" readonly step="0.1"></td>
                <td data-label="Tach Out"><span class="print-only">${log.tachOut || ''}</span><input type="number" name="tachOut" class="no-print" value="${log.tachOut || ''}" readonly step="0.1"></td>
                <td data-label="Tach In"><span class="print-only">${log.tachIn || ''}</span><input type="number" name="tachIn" class="no-print" value="${log.tachIn || ''}" readonly step="0.1"></td>
                <td class="delete-cell no-print" data-label=""><button class="delete-log-icon"><i class="fa-solid fa-trash-can fa-xl"></i></button></td>
            `;
            document.getElementById('logbook-body').insertBefore(newRow, document.querySelector('.add-log-row'));

            // Attach delete listener to only this new row's button
            const newDeleteButton = newRow.querySelector('.delete-log-icon');
            newDeleteButton.addEventListener('click', async () => {
                const row = newDeleteButton.closest('tr');
                const id = row.dataset.id;
                const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
                const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
                try {
                    const deleteResponse = await axios.delete(`/deleteflightlog/${id}`, {
                        headers: { [csrfHeader]: csrfToken }
                    });
                    row.remove();
                    const { newHobbs, newTach } = deleteResponse.data;
                    if (newHobbs !== undefined) {
                        document.getElementById('current-hobbs').value = newHobbs;
                        document.getElementById('current-hobbs-display').textContent = `Hobbs Time: ${newHobbs}`;
                        previousHobbsHours = newHobbs;
                    }
                    if (newTach !== undefined) {
                        document.getElementById('current-tach').value = newTach;
                        document.getElementById('current-tach-display').textContent = `Tach Time: ${newTach}`;
                        previousTachHours = newTach;
                    }
                    markUpdatedNow('hobbs-updated', 'flightlog');
                    markUpdatedNow('tach-updated', 'flightlog');
                    updateAllTimeLeft();
                    updateAddRowTimeLeft();
                } catch (error) {
                    console.error('Error deleting log:', error);
                }
            });

        // Update hours display from backend-calculated totals
        if (log.newHobbs !== undefined && log.newHobbs !== null) {
            document.getElementById('current-hobbs').value = log.newHobbs;
            document.getElementById('current-hobbs-display').textContent = `Hobbs Time: ${log.newHobbs}`;
            previousHobbsHours = log.newHobbs;
        }
        if (log.newTach !== undefined && log.newTach !== null) {
            document.getElementById('current-tach').value = log.newTach;
            document.getElementById('current-tach-display').textContent = `Tach Time: ${log.newTach}`;
            previousTachHours = log.newTach;
        }
        markUpdatedNow('hobbs-updated', 'flightlog');
        markUpdatedNow('tach-updated', 'flightlog');
        updateAllTimeLeft();
        updateAddRowTimeLeft();

        // Clear inputs
        document.getElementById('fromAirport').value = '';
        document.getElementById('toAirport').value = '';
        document.getElementById('hobbsIn').value = '';
        document.getElementById('hobbsOut').value = '';
        document.getElementById('tachIn').value = '';
        document.getElementById('tachOut').value = '';
        } catch (error) {
            // 400 = validation error from the server (e.g. partial pair, negative duration).
            // Keep the user's typed values intact so they can correct and retry —
            // the whole point of this code path is "don't hurt the user."
            const serverMsg = error?.response?.data?.message;
            const status    = error?.response?.status;
            if (status === 400 && serverMsg) {
                showToast(serverMsg, 'error');
            } else {
                console.error('Error adding log:', error);
                showToast('Failed to add flight log.', 'error');
            }
        }
    })

});

    // Delete log row (server-rendered rows)
    document.querySelectorAll('.delete-log-icon').forEach(button => {
        button.addEventListener('click', async () => {
            const row = button.closest('tr');
            const id = row.dataset.id;
            const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute('content');
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute('content');
            try {
                const deleteResponse = await axios.delete(`/deleteflightlog/${id}`, {
                    headers: { [csrfHeader]: csrfToken }
                });
                row.remove();
                console.log(`Log ${id} deleted`);
                const { newHobbs, newTach } = deleteResponse.data;
                if (newHobbs !== undefined) {
                    document.getElementById('current-hobbs').value = newHobbs;
                    document.getElementById('current-hobbs-display').textContent = `Hobbs Time: ${newHobbs}`;
                    previousHobbsHours = newHobbs;
                }
                if (newTach !== undefined) {
                    document.getElementById('current-tach').value = newTach;
                    document.getElementById('current-tach-display').textContent = `Tach Time: ${newTach}`;
                    previousTachHours = newTach;
                }
                markUpdatedNow('hobbs-updated', 'flightlog');
                markUpdatedNow('tach-updated', 'flightlog');
                updateAllTimeLeft();
                updateAddRowTimeLeft();
            } catch (error) {
                console.error('Error deleting log:', error);
            }
        });
    });
