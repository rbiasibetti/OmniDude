package com.omnidude.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /**
     * Cliente SSE que sumiu (aba fechada/recarregada). O Spring só reconhece isso quando a mensagem do JDK está
     * em inglês ("Broken pipe"); em SOs localizados ("Pipe quebrado") o erro vazaria como ERROR com stack trace.
     * Nenhum endpoint da API faz I/O próprio, então toda IOException aqui é falha de escrita para o cliente.
     */
    /**
     * Corpo ilegível ou com valor de enum desconhecido. Sem este tratador, o Spring procura pela causa da exceção e cai
     * no tratador de {@link IOException} (que não devolve nada): a requisição inválida responderia 200 e vazia.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.debug("Corpo inválido: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "Corpo da requisição inválido (JSON malformado ou valor desconhecido)"));
    }

    @ExceptionHandler(IOException.class)
    void clientGone(IOException e, HttpServletRequest request) {
        log.debug("Conexão encerrada pelo cliente em {}: {}", request.getRequestURI(), e.toString());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> integrity(DataIntegrityViolationException e) {
        log.warn("Violação de integridade: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Conflito com dados existentes"));
    }
}
