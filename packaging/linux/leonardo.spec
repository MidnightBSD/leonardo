Name: leonardo
Version: 0.1.0
Release: 1%{?dist}
Summary: S3-compatible object storage server
License: Apache-2.0
URL: https://github.com/MidnightBSD/leonardo
Source0: leonardo-%{version}.tar

BuildArch: noarch
BuildRequires: java-21-openjdk-devel
Requires: java-21-openjdk-headless
Requires(pre): shadow-utils

%description
Leonardo is an S3-compatible object storage server that turns a POSIX
filesystem directory into an S3 endpoint. It implements the AWS S3 REST
API including buckets, objects, multipart uploads, versioning, object
locking, lifecycle policies, ACLs, presigned URLs, and SigV4/SigV2
authentication.

%prep
%setup -q

%install
mkdir -p %{buildroot}%{_datadir}/java/leonardo/lib
cp lib/*.jar %{buildroot}%{_datadir}/java/leonardo/lib/

mkdir -p %{buildroot}%{_bindir}
cp bin/leonardo %{buildroot}%{_bindir}/leonardo
sed -i 's|APP_HOME=.*|APP_HOME=%{_datadir}/java/leonardo|' \
    %{buildroot}%{_bindir}/leonardo

mkdir -p %{buildroot}%{_sysconfdir}/leonardo
install -m 0644 %{_sourcedir}/leonardo.yaml.sample \
    %{buildroot}%{_sysconfdir}/leonardo/leonardo.yaml.sample
install -m 0600 %{_sourcedir}/api-keys.yaml.sample \
    %{buildroot}%{_sysconfdir}/leonardo/api-keys.yaml.sample

mkdir -p %{buildroot}%{_unitdir}
install -m 0644 %{_sourcedir}/leonardo.service \
    %{buildroot}%{_unitdir}/leonardo.service

%pre
getent group leonardo >/dev/null || groupadd -r leonardo
getent passwd leonardo >/dev/null || \
    useradd -r -g leonardo -d /var/lib/leonardo -s /sbin/nologin \
            -c "Leonardo S3 server" leonardo

%post
%systemd_post leonardo.service
mkdir -p /var/lib/leonardo /var/log/leonardo
chown leonardo:leonardo /var/lib/leonardo /var/log/leonardo
chmod 0750 /var/lib/leonardo /var/log/leonardo

%preun
%systemd_preun leonardo.service

%postun
%systemd_postun_with_restart leonardo.service

%files
%license LICENSE
%{_bindir}/leonardo
%{_datadir}/java/leonardo/
%{_unitdir}/leonardo.service
%config(noreplace) %{_sysconfdir}/leonardo/
%attr(0600,leonardo,leonardo) %{_sysconfdir}/leonardo/api-keys.yaml.sample
%ghost %attr(0750,leonardo,leonardo) /var/lib/leonardo
%ghost %attr(0750,leonardo,leonardo) /var/log/leonardo

%changelog
* Mon Jun 02 2026 Lucas Holt <luke@foolishgames.com> - 0.1.0-1
- Initial package
