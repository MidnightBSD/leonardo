# MidnightBSD mports Makefile for Leonardo

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
	# mport install may return non-zero even on success (mport 4.0.4 bug);
	# callers should use "mport install leonardo || true" if needed.
	@${ECHO_MSG} ""
	@${ECHO_MSG} "===> Leonardo installed."
	@${ECHO_MSG} "     1. Copy ${PREFIX}/etc/leonardo/leonardo.yaml.sample and edit it."
	@${ECHO_MSG} "     2. Copy ${PREFIX}/etc/leonardo/api-keys.yaml.sample,"
	@${ECHO_MSG} "        add your keys, then chmod 0600 the file."
	@${ECHO_MSG} "     3. Add 'leonardo_enable=YES' to /etc/rc.conf"
	@${ECHO_MSG} "     4. service leonardo start"
	@${ECHO_MSG} ""

.include <bsd.port.mk>
