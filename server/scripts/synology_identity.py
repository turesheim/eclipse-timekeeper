"""Package identities that can coexist during a full-instance migration."""
PACKAGES = {'timekeeper': 'Timekeeper', 'dagsverk': 'Dagsverk'}


def require_identity(package_id):
    if not isinstance(package_id,str) or package_id not in PACKAGES:
        raise ValueError('Choose the timekeeper or dagsverk package identity')
    return package_id


def application_id(package_id):
    return 'net.resheim.' + require_identity(package_id)


def package_guide(text, package_id):
    require_identity(package_id)
    text=text.replace('/var/packages/timekeeper/','/var/packages/'+package_id+'/')
    text=text.replace('SYNOPKG_PKGNAME=timekeeper','SYNOPKG_PKGNAME='+package_id)
    text=text.replace('sudo -u timekeeper','sudo -u '+package_id)
    text=text.replace('timekeeper-server',package_id+'-server')
    return '**Package identity: `'+package_id+'` ('+PACKAGES[package_id]+').**\n\n'+text
