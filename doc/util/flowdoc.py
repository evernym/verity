import os
import re

package_pat = re.compile(r'^\s*package\s+([a-zA-Z0-9_.]+)\s*$', re.MULTILINE)
class_pat = re.compile(r'^\s*(?:(?:abstract|sealed)\s+)*(class|trait|object)\s+([a-zA-Z0-9_]+)([^{]*){', re.MULTILINE)
relationship_pat = re.compile(r'[ \t\r\n]+(extends|with)[ \t\r\n]+([a-zA-Z9-9_]+)')

'''
A step in a flow diagram is documented with a note (comment) in the following
format (showing the most complex possibility):

// flow diagram: FlowA.branch1+FlowB, step 3.1; FlowC, step 4 -- explanation.

Flow names are not case-sensitive. They can contain spaces and other punctuation,
but not a hyphen or the plus symbol. The ".branch1" suffix is used to document
branches in the flow and creates a puml "group" box inside its parent diagram.

The simplest form would be omit the comment and the dotted notation for step
numbers, and describe a single flow:

// flow diagram: FlowA, step 3

Everything must appear on a single line, and all flows documented in that line
share the same explanation. If the same func participates in multiple flows,
but does different things (thus needing a different explanation), just add
another comment on a separate line

We use a set of increasingly precise regexes to parse these notes. 
'''
# finds all flow diagram notes in code
note_pat = re.compile(r'^\s*//\s*flow diagram:[ \t]*([^\r\n]+)', re.M | re.I)
# splits notes into name+num section plus explanation
step_split_expl_pat = re.compile(r'\s*([^-]*)(?:(?:-+\s*)(.*))?')
# parses name+num section
step_name_and_num_pat = re.compile(r'\s*([^,]*?),?[ \t]*step[ \t]*(\d+([.]\d+)?)', re.I)
squeeze_pat = re.compile(r'\s+')

'''
The type of method def we're searching for looks like the following, in complex form:

  def preMsgProcessing(msgType: MsgType, senderVerKey: 
      Option[VerKey])(implicit rmc: ReqMsgContext): Future[Any] = {

In reversed text (which we use to do a backwards regex search), it looks like this:

  { = ]ynA[erutuF :)txetnoCgsMqeR :cmr ticilpmi()]yeKreV[noitpO      \n
  :yeKreVrednes ,epyTgsM :epyTgsm(gnissecorPgsMerp fed  '
'''
preceding_def_pat = re.compile(r'{\s*=[^:]+:(?:.*?)\s*([a-zA-Z0-9_]+)\s+fed([ \t]+(etavirp|detcetorp))?[ \t]*\n', re.DOTALL)
relative_to = os.path.dirname(os.path.abspath(__file__))

'''Support multiple flows at same comment line (A|B) step 1, C step 2 -- comment
Support branches with alt?
fix bug with () inside params to func'''

def normpath(path):
    return os.path.normpath(os.path.join(relative_to, path))

def sort_note_by_num_then_group(note):
    key = '{:.1f}'.format(note.step.num).rjust(4,'0') + note.step.group
    return key

def collect(path):
    flows = {}
    path = normpath(path)
    for root, dirs, files in os.walk(path):
        for f in [x for x in files if x.endswith('.scala')]:
            notes = parse_file(os.path.join(root, f))
            if notes:
                for note in notes:
                    flow = note.step.flow
                    if flow not in flows:
                        flows[flow] = []
                    flows[flow].append(note)
    for key in flows.keys():
        flows[key].sort(key=sort_note_by_num_then_group)
    return flows


def get_pkg_and_classes(txt):
    pkg = None
    pkg_match = package_pat.search(txt)
    if pkg_match:
        pkg = pkg_match.group(1)
    # Find classes in the file. We need this so we can understand the
    # scope of any functions that are part of whatever comments we find
    # that give us instructions about flow diagrams.
    classes = []
    for class_match in class_pat.finditer(txt):
        classes.append(class_match)
    return pkg, classes


def parse_file(fname):
    with open(fname, 'rt') as f:
        txt = f.read()
    reversed = pkg = classes = None
    notes = []
    for note_match in note_pat.finditer(txt):
        # Only pay the cost for expensive searching if we find actual diagram note.
        if not reversed:
            reversed = txt[::-1]
            pkg, class_matches = get_pkg_and_classes(txt)
            class_matches.reverse() # we want to search list from end
        # Search backward for preceding def.
        note_offset_in_reverse = len(txt) - note_match.start()
        preceding_def = preceding_def_pat.search(reversed, note_offset_in_reverse)
        if preceding_def:
            func_name = preceding_def.group(1)[::-1]
            class_name = get_containing_class(class_matches, len(txt) - preceding_def.start())
            split_match = step_split_expl_pat.search(note_match.group(1))
            name_num_chunk = split_match.group(1).strip()
            expl = split_match.group(2)
            if expl is None:
                expl = ''
            expl = squeeze_pat.sub(' ', expl.strip())
            if expl == '.':
                expl = ''
            steps = get_steps(name_num_chunk, expl)
            for step in steps:
                notes.append(Note(step, pkg, class_name, func_name, fname, note_match.start()))
        else:
            raise Exception("Couldn't find def that preceded %s in %s." % (note_match.group(0).strip(), fname))
    return notes


class Note:
    def __init__(self, step, pkg, class_name, func_name, fname, offset):
        self.step = step
        self.pkg = pkg if pkg else ''
        self.class_name = class_name
        self.func_name = func_name
        self.fname = fname
        self.offset = offset


class StepInFlow:
    def __init__(self, flow, num, expl):
        i = flow.find('.')
        if i > -1:
            self.flow = flow[:i]
            self.group = flow[i + 1:]
        else:
            self.flow = flow
            self.group = ''
        self.num_as_text = num
        self.num = float(self.num_as_text)
        self.expl = expl


def get_steps(name_num_chunk, expl):
    items = []
    different_numbers = [x.strip().lower() for x in name_num_chunk.split(';')]
    for chunk in different_numbers:
        m = step_name_and_num_pat.search(chunk)
        if m:
            num = m.group(2)
            flow_names = [fn.strip() for fn in m.group(1).split('+')]
            for fn in flow_names:
                items.append(StepInFlow(fn, num, expl))
    return items


def get_containing_class(class_matches, just_before_offset):
    for cm in class_matches:
        if cm.start() < just_before_offset:
            return cm.group(2)


def wrap(txt, func_len):
    wrap_at = max(func_len, 40)
    start_line = 0
    line_len = len(txt)
    while line_len > wrap_at:
        end_line = start_line + wrap_at
        while txt[end_line] != ' ':
            end_line -= 1
            # Just in case we have massive tokens that can't be split,
            # pretend there was a space at the max line len.
            if end_line <= start_line + 20:
                end_line = start_line + wrap_at
                txt = txt[:end_line] + ' ' + txt[:end_line]
                break
        start_line = end_line + 2
        txt = txt[:end_line] + '\\n' + txt[end_line + 1:]
        line_len = len(txt) - start_line
    return txt

def write_group(notes, i, groups, class_that_sent_to_group, f):
    g_name = notes[i].step.group
    g_num, g_count = groups.get(g_name, (len(groups) + 1, 0))
    g_count += 1
    f.write('\ngroup %d00: %s\n' % (g_num, g_name))
    start_num =  (100 * g_num) + g_count
    f.write('autonumber %d\n' % start_num)
    last_class = class_that_sent_to_group
    try:
        while True:
            # Write any note that's part of this group. Skip notes in
            # other groups that have parallel numbers.
            if notes[i].step.group == g_name:
                note = notes[i]
                last_class = write_arrow(note.class_name, last_class, f)
                write_note(notes[i], f)
                notes[i] = None
                g_count += 1
            i += 1
            # Continue writing in this group until we run out of notes
            # or we revert back to an empty group.
            if i >= len(notes):
                return
            if not notes[i].step.group:
                write_arrow(notes[i].class_name, last_class, f)
                return
    finally:
        groups[g_name] = (g_num, g_count)
        f.write('end\n')


def write_note(note, f):
    puml_note = '%s( )' % note.func_name
    if note.step.expl:
        puml_note = wrap(puml_note + ' -- ' + note.step.expl, len(note.func_name) + 3)
    f.write('note over %s #ffffff: %s\n' % (note.class_name, puml_note))


def write_arrow(next_class, last_class, f):
    if last_class:
        arrow = '-->' if next_class == last_class else '->'
        f.write('%s %s %s\n' % (last_class, arrow, next_class))
    return next_class


def generate(flows, path):
    for name, notes in flows.items():
        fname = os.path.join(normpath(path), '%s_flow.puml' % name)
        if os.path.exists(fname):
            os.remove(fname)
        outer_stepnum = 1
        with open(fname, 'wt') as f:
            f.write('@startuml\n\ntitle Calls in the "%s" Flow\n' % name)
            groups = {}
            last_class = None
            restart_autonumber = True
            i = 0
            while True:
                note = notes[i]
                # Skip notes previously written and nulled out as part of a group.
                if note:
                    # Are we starting a new group?
                    if note.step.group:
                        write_group(notes, i, groups, last_non_group_class, f)
                        restart_autonumber = True
                        last_class = None
                    else:
                        if restart_autonumber:
                            f.write('\nautonumber %d\n' % outer_stepnum)
                            restart_autonumber = False
                        last_class = last_non_group_class = write_arrow(note.class_name, last_class, f)
                        outer_stepnum += 1
                        write_note(note, f)
                i += 1
                if i >= len(notes):
                    break
            f.write('\n@enduml\n')


if __name__ == '__main__':
    flows = collect('../../verity/src/main/scala/com/evernym/verity/')
    generate(flows, '../')