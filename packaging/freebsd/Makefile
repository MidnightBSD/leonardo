# $FreeBSD$

PORTNAME=	leonardo
PORTVERSION=	0.1.0
CATEGORIES=	net
MASTER_SITES=	https://github.com/MidnightBSD/leonardo/releases/download/v${PORTVERSION}/
DISTFILES=	leonardo-${PORTVERSION}.tar${EXTRACT_SUFX}

MAINTAINER=	luke@foolishgames.com
COMMENT=	S3-compatible object storage server
WWW=		https://github.com/MidnightBSD/leonardo

LICENSE=	APACHE20

BUILD_DEPENDS=	${JAVA_HOME}/bin/java:java/openjdk21
RUN_DEPENDS=	${JAVA_HOME}/bin/java:java/openjdk21

USES=		java
JAVA_VERSION=	21
USE_RC_SUBR=	leonardo

USERS=		leonardo
GROUPS=		leonardo

NO_ARCH=	yes
NO_BUILD=	yes

PLIST_SUB=	JAVA="${JAVA_VERSION}"

do-install:
	${MKDIR} ${STAGEDIR}${PREFIX}/share/java/leonardo/lib
	${INSTALL_DATA} ${WRKSRC}/lib/*.jar \
		${STAGEDIR}${PREFIX}/share/java/leonardo/lib/
	${INSTALL_SCRIPT} ${WRKSRC}/bin/leonardo \
		${STAGEDIR}${PREFIX}/bin/leonardo
	${MKDIR} ${STAGEDIR}${PREFIX}/etc/leonardo
	${INSTALL_DATA} ${FILESDIR}/leonardo.yaml.sample \
		${STAGEDIR}${PREFIX}/etc/leonardo/leonardo.yaml.sample
	${INSTALL_DATA} ${FILESDIR}/api-keys.yaml.sample \
		${STAGEDIR}${PREFIX}/etc/leonardo/api-keys.yaml.sample

post-install:
	@${ECHO_MSG} ""
	@${ECHO_MSG} "===> Leonardo installed."
	@${ECHO_MSG} "     1. Copy ${PREFIX}/etc/leonardo/leonardo.yaml.sample to"
	@${ECHO_MSG} "        ${PREFIX}/etc/leonardo/leonardo.yaml and edit it."
	@${ECHO_MSG} "     2. Copy ${PREFIX}/etc/leonardo/api-keys.yaml.sample to"
	@${ECHO_MSG} "        ${PREFIX}/etc/leonardo/api-keys.yaml and add your keys."
	@${ECHO_MSG} "     3. chmod 0600 ${PREFIX}/etc/leonardo/api-keys.yaml"
	@${ECHO_MSG} "     4. Add 'leonardo_enable=YES' to /etc/rc.conf"
	@${ECHO_MSG} "     5. service leonardo start"
	@${ECHO_MSG} ""

.include <bsd.port.mk>
