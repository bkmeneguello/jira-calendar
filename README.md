* Configurar a autenticação:
 - A autenticação será verificada na seguinte ordem de precedencia
	1º Linha de comando
	jiracal.sh <Agenda> <Usuário> <Senha>
	
	2º Propriedades da VM
	export JAVA_OPTS
	-Dgoogle.calendar=<Agenda>
	-Djira.username=<Usuário>
	-Djira.password=<Senha>
	
	3º Arquivo de propriedades
	$HOME/.credentials/jira.properties
	calendar=<Agenda>
	username=<Usuário>
	password=<Senha>

* Autorizar a app no Google

* Criar uma agenda

* Lançar os apontamentos com o nome começando com o código da issue XXX-YYYY
 - A descrição do evento será a descrição do worklog

* Executar o jiracal.sh