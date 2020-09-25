import os
import re

package_pat = re.compile(r'^\s*package\s+([a-zA-Z0-9_.]+)\s*$', re.MULTILINE)
class_pat = re.compile(r'^\s*(?:(?:abstract|sealed)\s+)*(class|trait|object)\s+([a-zA-Z0-9_]+)([^{]*){', re.MULTILINE)
relationship_pat = re.compile(r'[ \t\r\n]+(extends|with)[ \t\r\n]+([a-zA-Z9-9_]+)')
relative_to = os.path.dirname(os.path.abspath(__file__))
ns = False


def normpath(path):
    return os.path.normpath(os.path.join(relative_to, path))


def collect(path, items):
    path = normpath(path)
    for root, dirs, files in os.walk(path):
        for f in [x for x in files if x.endswith('.scala')]:
            for c in parse_file(os.path.join(root, f)):
                # This allows collisions that differ only by namespace. That's okay.
                items[c.name] = c


def parse_file(fname):
    with open(fname, 'rt') as f:
        txt = f.read()
    pkg = None
    pkg_match = package_pat.search(txt)
    if pkg_match:
        pkg = pkg_match.group(1)
    classes = []
    for class_match in class_pat.finditer(txt):
        classes.append(Decl(class_match.group(2), pkg, class_match.group(1), fname, class_match.start(), class_match.group()))
    return classes

class Relationship:
    def __init__(self, dep_match):
        self.name = dep_match.group(2)
        self.typ = dep_match.group(1)

class Decl:
    def __init__(self, name, pkg, typ, fname, offset, relationships_block):
        self.name = name
        self.pkg = pkg if pkg else ''
        self.typ = typ
        self.fname = fname
        self.offset = offset
        self.relationships = []
        for relationship_match in relationship_pat.finditer(relationships_block):
            self.relationships.append(Relationship(relationship_match))
    @property
    def puml_type(self):
        return 'interface' if self.typ == 'trait' else 'class'


def declare_item(item_name, items, done, f, focus):
    if item_name not in done:
        done[item_name] = True
        item = items.get(item_name)
        if item:
            focused = ' <<focus>>' if focus else ''
            if ns:
                f.write('%s %s.%s%s\n' % (item.puml_type, item.pkg, item_name, focused))
            else:
                f.write('%s %s%s\n' % (item.puml_type, item.name, focused))
            for rel in item.relationships:
                declare_item(rel.name, items, done, f, False)
        else:
            f.write('abstract class %s <<sys>>\n' % item_name)

def relate_item(item_name, items, done, f):
    if item_name not in done:
        done[item_name] = True
        if item_name in items:
            item = items[item_name]
            item_depth = item.pkg.count('.')
            to_recurse = []
            for rel in item.relationships:
                ditem = items.get(rel.name)
                pkg = ditem.pkg if ditem else ''
                arrow = '--*' if rel.typ == 'with' else '-up-|>'
                if rel.typ == 'with':
                    if item.pkg == pkg:
                        arrow = '-*' # use horiz layout for traits in same pkg
                    elif pkg.count('.') < item_depth: # suggest more generic trait goes above.
                        arrow = '-down-*'
                if ns:
                    f.write('%s.%s %s %s.%s\n' % (item.pkg, item_name, arrow, pkg, rel.name))
                else:
                    f.write('%s %s %s\n' % (item_name, arrow, rel.name))
                to_recurse.append(rel)
            for rel in to_recurse:
                relate_item(rel.name, items, done, f)


def generate(focus_items, items, path):
    fname = normpath(path)
    if os.path.exists(fname):
        os.remove(fname)
    with open(fname, 'wt') as f:
        f.write('''@startuml
skinparam class {
    BackgroundColor<<focus>> PaleGreen
    BorderColor<<focus>> Black
    BackgroundColor<<sys>> Tomato
    BorderColor<<sys>> Black
}
''')
        done = {}
        for item_name in focus_items:
            declare_item(item_name, items, done, f, True)
        f.write('\n')
        done = {}
        for item_name in focus_items:
            relate_item(item_name, items, done, f)
        f.write('\n@enduml\n')


if __name__ == '__main__':
    suffix = '_ns' if ns else ''
    items = {}
    collect('../../verity/src/main/scala/com/evernym/verity/', items)
    generate(['UserAgent', 'UserAgentPairwise', 'AgencyAgent', 'AgencyAgentPairwise'],
             items, '../agent_classes%s.puml' % suffix)